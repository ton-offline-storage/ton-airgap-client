package com.tonairgapclient.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.snackbar.Snackbar
import com.tonairgapclient.R
import com.tonairgapclient.blockchain.TonlibController
import com.tonairgapclient.blockchain.TonlibController.getAccountValues
import com.tonairgapclient.blockchain.TonlibController.getFullAccountState
import com.tonairgapclient.blockchain.TonlibController.getSeqno
import com.tonairgapclient.blockchain.TonlibController.getTransactions
import com.tonairgapclient.datamodels.AccountValues
import com.tonairgapclient.scrolling.TransactionItemAdapter
import com.tonairgapclient.storage.AccountsKeeper
import com.tonairgapclient.utils.trimZeros
import io.github.thibseisel.identikon.Identicon
import io.github.thibseisel.identikon.IdenticonStyle
import io.github.thibseisel.identikon.drawToBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ton.api.tonnode.TonNodeBlockIdExt
import org.ton.block.MsgAddressInt
import org.ton.lite.client.internal.TransactionId


class AccountExplorerActivity : AppCompatActivity() {
    companion object {
        private const val UPDATE_TIMEOUT: Long = 7000L
    }
    private var accountIndex: Int = 0
    private lateinit var balanceLabel: TextView
    private lateinit var seqnoLabel: TextView
    private lateinit var emptyPlaceholder: TextView
    private lateinit var adapter: TransactionItemAdapter
    private lateinit var recyclerView: RecyclerView
    private var isLoadingOldTxs: Boolean = false
    private lateinit var accountAddress: String
    private lateinit var lastDisplayedBlock: TonNodeBlockIdExt
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_explorer)
        accountIndex = intent.getIntExtra("index", 0)
        Log.d("Debug", "CREATED for $accountIndex")
        emptyPlaceholder = findViewById(R.id.empty_placeholder)
        val addressLabel: TextView = findViewById(R.id.address_label)
        balanceLabel = findViewById(R.id.balance_label)
        seqnoLabel = findViewById(R.id.seqno_val_label)
        val identicon: ImageView = findViewById(R.id.identicon)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

        addressLabel.setOnClickListener{
            val clip = ClipData.newPlainText("Address", addressLabel.text)
            clipboard.setPrimaryClip(clip)
            showAddressCopiedSnackBar()
        }

        accountAddress = AccountsKeeper.getAccount(accountIndex).address
        addressLabel.text = accountAddress
        val accountBalance = AccountsKeeper.getBalance(accountIndex)
        balanceLabel.text = getString(R.string.ton_amount, trimZeros(accountBalance.toString()),
            TonlibController.getTonPriceString(accountBalance))
        seqnoLabel.text = AccountsKeeper.getAccount(accountIndex).seqno.toString()

        val identiconResolution = resources.displayMetrics.heightPixels / 10
        val icon = Identicon.fromValue(accountAddress, size = identiconResolution, style = IdenticonStyle(padding = 0F))
        val targetBitmap = Bitmap.createBitmap(identiconResolution, identiconResolution, Bitmap.Config.ARGB_8888)
        icon.drawToBitmap(targetBitmap)
        identicon.setImageBitmap(targetBitmap)

        val swipeLayout: SwipeRefreshLayout = findViewById(R.id.swipe_layout)
        swipeLayout.setColorSchemeResources(R.color.blue)
        swipeLayout.setOnRefreshListener {
            lifecycleScope.launch {
                Log.d("Debug", "Updating...")
                updateTransactions()
                Log.d("Debug", "Done...")
                swipeLayout.isRefreshing = false
            }
        }
        swipeLayout.post {
            swipeLayout.isRefreshing = true
            lifecycleScope.launch {
                updateTransactions()
                swipeLayout.isRefreshing = false
                Log.d("Debug", "Finished initial update")
                lifecycleScope.launch {
                    Log.d("Debug", "Started Txn updater")
                    while (true) {
                        delay(UPDATE_TIMEOUT)
                        regularTxsUpdate()
                    }
                }
            }
        }
        recyclerView = findViewById(R.id.recycler_view)
        recyclerView.setHasFixedSize(true)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = TransactionItemAdapter(accountIndex, this, clipboard)
        adapter = recyclerView.adapter as TransactionItemAdapter
        (recyclerView.adapter as TransactionItemAdapter).registerEmptyObserver(emptyPlaceholder)
        addScrolledToBottomListener()
        if(AccountsKeeper.getAccountTxsNum(accountIndex) == 0) {
            emptyPlaceholder.visibility = View.VISIBLE
        }
    }
    fun showAddressCopiedSnackBar() {
        val customSnackView: View = layoutInflater.inflate(R.layout.upper_snack, findViewById(R.id.root_layout), false)
        val snackbar = Snackbar.make(findViewById(R.id.upper_snack_place), "", Snackbar.LENGTH_SHORT)
        val snackbarLayout: Snackbar.SnackbarLayout = snackbar.view as? Snackbar.SnackbarLayout ?: return
        snackbarLayout.setBackgroundColor(Color.TRANSPARENT)
        snackbarLayout.addView(customSnackView, 0)
        snackbar.show()
    }
    override fun onDestroy() {
        super.onDestroy()
        Log.d("Debug", "DESTROYED FOR $accountIndex")
    }
    override fun onPause() {
        super.onPause()
        Log.d("Debug", "Pause FOR $accountIndex")
    }
    private fun addScrolledToBottomListener() {
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (!isLoadingOldTxs) {
                    val linearLayoutManager = recyclerView.layoutManager as LinearLayoutManager?
                    if (linearLayoutManager != null && AccountsKeeper.needLoading(accountIndex)
                        && linearLayoutManager.findLastVisibleItemPosition() >= AccountsKeeper.getAccountTxsNum(accountIndex) - 1) {
                        Log.d("Debug", "Loading triggered")
                        isLoadingOldTxs = true
                        lifecycleScope.launch(Dispatchers.IO) {
                            loadMoreTxs()
                        }
                    }
                }
            }
        })
    }
    private suspend fun loadMoreTxs() {
        val oldestTxn = AccountsKeeper.getAccountTxs(accountIndex).last()
        val oldTxs = getTransactions(MsgAddressInt(accountAddress),
            TransactionId(oldestTxn.prevTxnHash.toByteArray(), oldestTxn.prevTxnLt)) ?: return
        Log.d("Debug", "Loaded " + oldTxs.size + "more transactions")
        AccountsKeeper.addOldTxs(accountIndex, oldTxs, adapter)
        AccountsKeeper.store(this.applicationContext)
        isLoadingOldTxs = false
    }
    private suspend fun regularTxsUpdate() {
        val balance = AccountsKeeper.getBalance(accountIndex)
        balanceLabel.text = getString(R.string.ton_amount, trimZeros(balance.toString()),
            TonlibController.getTonPriceString(balance))
        updateTransactionsWithCachedBlock()
    }
    private suspend fun updateTransactionsWithCachedBlock() {
        Log.d("Debug", "Updating transactions with cached block for: $accountAddress")
        val fullState = getFullAccountState(accountAddress,
            TonlibController.getCachedLastBlockId()
        ) ?: return
        Log.d("Debug", "Got state for $accountAddress")
        val latestTxn = AccountsKeeper.getAccountTxs(accountIndex).firstOrNull()
        if(latestTxn != null && TransactionId(latestTxn.hash.toByteArray(), latestTxn.lt) == fullState.lastTransactionId) {
            Log.d("Debug", "No new transactions")
            return
        }
        Log.d("Debug", "Last txn:" + fullState.lastTransactionId)
        val status = getAccountValues(fullState)
        if(status != null) {
            AccountsKeeper.updateBalance(accountIndex, status.balance)
            Log.d("Debug", "Balance updated")
            AccountsKeeper.store(this.applicationContext)
            Log.d("Debug", "Stored")
            balanceLabel.text = getString(R.string.ton_amount, trimZeros(status.balance.toString()),
                TonlibController.getTonPriceString(status.balance))

            Log.d("Debug", "Getting seqno")
            val seqno = if(status.state != AccountValues.State.UNINIT) getSeqno(accountAddress,
                TonlibController.getCachedLastBlockId()
            ) else 0
            Log.d("Debug", "Got seqno")
            if(seqno != null) {
                AccountsKeeper.updateSeqno(accountIndex, seqno)
                seqnoLabel.text = seqno.toString()
                AccountsKeeper.store(this.applicationContext)
            }
        }
        val lastTxId = fullState.lastTransactionId ?: return
        val newTxs = getTransactions(fullState.address, lastTxId) ?: return
        Log.d("Debug", "Managed to get: " + newTxs.size + " transactions")
        AccountsKeeper.addNewTxs(accountIndex, newTxs, adapter)
        if(AccountsKeeper.getAccountTxsNum(accountIndex) > 0) {
            recyclerView.layoutManager?.smoothScrollToPosition(recyclerView, null, 0)
        }
        AccountsKeeper.store(this.applicationContext)
    }
    private suspend fun updateTransactions() {
        Log.d("Debug", "Updating transactions on: $accountAddress")
        TonlibController.updateLastBlockId()
        if(this::lastDisplayedBlock.isInitialized && lastDisplayedBlock == TonlibController.getCachedLastBlockId()) {
            Log.d("Debug", "No new blocks appeared")
            return
        }
        lastDisplayedBlock = TonlibController.getCachedLastBlockId()
        updateTransactionsWithCachedBlock()
    }
}