package com.tonairgapclient.activities

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.selection.SelectionPredicates
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.tonairgapclient.R
import com.tonairgapclient.blockchain.TonlibController
import com.tonairgapclient.scrolling.AccountItemAdapter
import com.tonairgapclient.scrolling.AccountItemDetailsLookup
import com.tonairgapclient.scrolling.SelectionDetector
import com.tonairgapclient.storage.AccountsKeeper
import kotlinx.coroutines.launch

class WatchAccountsActivity : ComponentActivity() {
    private lateinit var popup: PopupWindow
    private lateinit var clipboard: ClipboardManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var addButton: FloatingActionButton
    private lateinit var deleteButton: FloatingActionButton
    private lateinit var emptyPlaceholder: TextView
    private lateinit var detector: SelectionDetector
    private var lastSwitchedIndex: Int? = null
    fun enterDeletion() {
        addButton.visibility = View.GONE
        deleteButton.visibility = View.VISIBLE
    }
    fun leaveDeletion() {
        deleteButton.visibility = View.GONE
        addButton.visibility = View.VISIBLE
    }
    fun switchToExplorer(index: Int) {
        val intent = Intent(this, AccountExplorerActivity::class.java)
        intent.putExtra("index", index)
        lastSwitchedIndex = index
        startActivity(intent)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watch_accounts)
        setupPopup()
        addButton = findViewById(R.id.fab)
        addButton.setOnClickListener(this::showPopup)
        deleteButton = findViewById(R.id.delete_fab)
        deleteButton.setOnClickListener{ deleteAccounts() }
        emptyPlaceholder = findViewById(R.id.empty_placeholder)
        clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        Log.d("Debug", "Settled")
        Log.d(
            "Debug",
            "VIEW WIDTH: " + resources.displayMetrics.widthPixels + " HEIGHT: " + resources.displayMetrics.heightPixels
        )

        recyclerView = findViewById(R.id.recycler_view)
        recyclerView.setHasFixedSize(true)
        recyclerView.adapter = AccountItemAdapter(
            this,
            resources.displayMetrics.heightPixels / 6)
        //registerTouchInterceptor()
        (recyclerView.adapter as AccountItemAdapter).registerEmptyObserver(emptyPlaceholder)
        (recyclerView.adapter as AccountItemAdapter).tracker = SelectionTracker.Builder(
            "Accounts selection",
            recyclerView,
            AccountItemAdapter.AccountItemKeyProvider(recyclerView),
            AccountItemDetailsLookup(recyclerView),
            StorageStrategy.createLongStorage()
        ).withSelectionPredicate(
            SelectionPredicates.createSelectAnything()
        ).build()
        detector = SelectionDetector((recyclerView.adapter as AccountItemAdapter).tracker!!,
            this, recyclerView.adapter as AccountItemAdapter
        )
        (recyclerView.adapter as AccountItemAdapter).tracker?.addObserver(detector)
        recyclerView.itemAnimator = null
        AccountsKeeper.viewsAdapter = recyclerView.adapter as AccountItemAdapter
        if(AccountsKeeper.size() == 0) {
            emptyPlaceholder.visibility = View.VISIBLE
        }
    }
    @Deprecated("Deprecated in Java", ReplaceWith("onBackPressedDispatcher.onBackPressed()"))
    override fun onBackPressed() {
        if(detector.isSelected()) {
            detector.deselect()
            leaveDeletion()
        } else {
            onBackPressedDispatcher.onBackPressed()
        }
    }
    private fun deleteAccounts() {
        Log.d("DebugS", "Deleting...")
        for(id in detector.selected()) {
            Log.d("Debug", "Going to remove: $id" + " With: " + AccountsKeeper.getAccount(id.toInt()).address)
        }
        leaveDeletion()
        //AccountsKeeper.removeAccounts(detector.selected())
        //recyclerView.adapter?.notifyDataSetChanged()
        for(pos in detector.selected().sortedDescending()) {
            AccountsKeeper.removeAccount(pos)
            recyclerView.adapter?.notifyItemRemoved(pos.toInt())
            recyclerView.adapter?.notifyItemRangeChanged(pos.toInt(), AccountsKeeper.size() - pos.toInt())
        }
        lifecycleScope.launch {
            Log.d("DebugS", "Saving deletions...")
            AccountsKeeper.store(this@WatchAccountsActivity.applicationContext)
            Log.d("DebugS", "Deletions Saved")
        }
        detector.deselect()
        Log.d("Debug", "Afterwards detector state" + detector.isSelected())
    }
    private fun addAccount(address: String) {
        Log.d("Debug", "adding")
        if(!TonlibController.validateAddress(address)) {
            Log.d("Debug", "Wrong")
            val alert = popup.contentView.findViewById<TextView>(R.id.wrong_address_alert)
            alert.text = getString(R.string.wrong_address)
            alert.visibility = View.VISIBLE
            val input = popup.contentView.findViewById<EditText>(R.id.address_input)
            input.backgroundTintList = AppCompatResources.getColorStateList(this, R.color.red)
            return
        }
        if(AccountsKeeper.findAccount(address) != null) {
            Log.d("Debug", "Address already exists")
            val alert = popup.contentView.findViewById<TextView>(R.id.wrong_address_alert)
            alert.text = getString(R.string.existing_address)
            alert.visibility = View.VISIBLE
            val input = popup.contentView.findViewById<EditText>(R.id.address_input)
            input.backgroundTintList = AppCompatResources.getColorStateList(this, R.color.red)
            return
        }
        popup.dismiss()
        AccountsKeeper.addAccount(address, this.applicationContext)
        lifecycleScope.launch { AccountsKeeper.store(this@WatchAccountsActivity.applicationContext) }
        recyclerView.adapter?.notifyItemInserted(AccountsKeeper.size() - 1)
        recyclerView.layoutManager?.smoothScrollToPosition(recyclerView, null, AccountsKeeper.size() - 1)
        (recyclerView.adapter as AccountItemAdapter).updateBalance(address, AccountsKeeper.size() - 1)
    }
    private fun setupPopup() {
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val popupView = inflater.inflate(R.layout.adding_account_popup, findViewById(R.id.popup_root), false)
        val addButton = popupView.findViewById<Button>(R.id.add_account_button)
        val pasteButton = popupView.findViewById<Button>(R.id.paste_button)
        val input = popupView.findViewById<EditText>(R.id.address_input)
        popupView.setOnTouchListener { v: View, _: MotionEvent? ->
            v.performClick()
            popup.dismiss()
            true
        }
        pasteButton.setOnClickListener{
            Log.d("Debug", "primary clip " + clipboard.hasPrimaryClip())
            Log.d("Debug", "is: " + clipboard.primaryClipDescription)
            if (clipboard.hasPrimaryClip() && (clipboard.primaryClipDescription!!.hasMimeType(
                    ClipDescription.MIMETYPE_TEXT_PLAIN) || clipboard.primaryClipDescription!!.hasMimeType(
                    ClipDescription.MIMETYPE_TEXT_HTML))
            ) {
                val item = clipboard.primaryClip!!.getItemAt(0)
                input.setText(item.text.toString())
                input.requestFocus()
            } else {
                val customSnackView: View = layoutInflater.inflate(
                    R.layout.empty_clip_snack, findViewById(
                        R.id.root_layout
                    ), false)
                val snackbar = Snackbar.make(popupView.findViewById(R.id.upper_snack_place), "", Snackbar.LENGTH_SHORT)
                val snackbarLayout: Snackbar.SnackbarLayout = snackbar.view as? Snackbar.SnackbarLayout ?: return@setOnClickListener
                snackbarLayout.setBackgroundColor(Color.TRANSPARENT)
                snackbarLayout.addView(customSnackView, 0)
                snackbar.show()
            }
        }
        input.setOnFocusChangeListener { _, hasFocus ->
            Log.d("Debug", "Focus became $hasFocus")
            val color = if(hasFocus) R.color.blue else R.color.black
            input.backgroundTintList = AppCompatResources.getColorStateList(this, color)
            popup.contentView.findViewById<TextView>(R.id.wrong_address_alert).visibility = View.GONE
        }
        input.addTextChangedListener {
            input.backgroundTintList = AppCompatResources.getColorStateList(this, R.color.blue)
            popup.contentView.findViewById<TextView>(R.id.wrong_address_alert).visibility = View.GONE
        }
        addButton.setOnClickListener{
            addAccount(input.text.toString())
        }
        popup = PopupWindow(
            popupView,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        popup.isFocusable = true

    }
    private fun showPopup(view: View) {
        runOnUiThread {
            popup.contentView.findViewById<EditText>(R.id.address_input).setText("")
            popup.contentView.findViewById<EditText>(R.id.address_input).backgroundTintList =
                AppCompatResources.getColorStateList(this, R.color.black)
            popup.contentView.findViewById<TextView>(R.id.wrong_address_alert).visibility = View.GONE
            popup.showAtLocation(view, Gravity.CENTER, 0, 0)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            recyclerView.adapter?.notifyItemChanged(lastSwitchedIndex!!)
        } catch (ignored: Exception) {}
        AccountsKeeper.viewsAdapter = recyclerView.adapter as AccountItemAdapter
        Log.d("Debug", "Watch resumed")
    }
    override fun onPause() {
        super.onPause()
        AccountsKeeper.viewsAdapter = null
    }
    override fun onDestroy() {
        super.onDestroy()
        AccountsKeeper.viewsAdapter = null
    }
}