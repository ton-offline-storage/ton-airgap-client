package com.tonairgapclient.scrolling

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.widget.RecyclerView
import com.tonairgapclient.Account
import com.tonairgapclient.R
import com.tonairgapclient.blockchain.TonlibController.getAccountValues
import com.tonairgapclient.activities.WatchAccountsActivity
import com.tonairgapclient.storage.AccountsKeeper
import com.tonairgapclient.utils.trimZeros
import io.github.thibseisel.identikon.Identicon
import io.github.thibseisel.identikon.IdenticonStyle
import io.github.thibseisel.identikon.drawToBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.ton.block.Coins


class AccountItemAdapter(private val activity: WatchAccountsActivity,
                         private val identiconResolution: Int)
    : ItemAdapter<AccountItemAdapter.AccountItemViewHolder>(){
    private val context: Context = activity.applicationContext
    private val activityScope: CoroutineScope = activity.lifecycleScope
    var tracker: SelectionTracker<Long>? = null
    init {
        setHasStableIds(true)
    }

    class AccountItemKeyProvider(private val recyclerView: RecyclerView) :
        ItemKeyProvider<Long>(SCOPE_MAPPED) {

        override fun getKey(position: Int): Long? {
            return recyclerView.adapter?.getItemId(position)
        }

        override fun getPosition(key: Long): Int {
            val viewHolder = recyclerView.findViewHolderForItemId(key)
            return viewHolder?.layoutPosition ?: RecyclerView.NO_POSITION
        }
    }

    class AccountItemViewHolder(view: View): RecyclerView.ViewHolder(view) {
        val addressLabel: TextView = view.findViewById(R.id.address_label)
        val balanceLabel: TextView = view.findViewById(R.id.balance_label)
        val identicon: ImageView = view.findViewById(R.id.identicon)
        val updateButton: ImageButton = view.findViewById(R.id.update_button)
        val progressBar: ProgressBar = view.findViewById(R.id.update_progress_bar)
        val inner: ConstraintLayout = view.findViewById(R.id.card_inner)
        val tick: ImageView = view.findViewById(R.id.tick)
        fun getItemDetails(): ItemDetailsLookup.ItemDetails<Long> =
            object : ItemDetailsLookup.ItemDetails<Long>() {
                override fun getPosition(): Int = adapterPosition
                override fun getSelectionKey(): Long = itemId
            }
    }

    override fun getItemId(position: Int): Long = position.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AccountItemViewHolder {
        val adapterLayout = LayoutInflater.from(parent.context).inflate(R.layout.account_card, parent, false)
        return AccountItemViewHolder(adapterLayout)
    }

    override fun getItemCount() = AccountsKeeper.size()

    private fun getBalanceString(coins: Coins) : String {
        val balanceString = trimZeros(coins.toString())
        return context.getString(
            R.string.ton_amount, balanceString)
    }
    fun updateBalance(address: String, position: Int) {
        AccountsKeeper.animate(position)
        notifyItemChanged(position)
        activityScope.launch(Dispatchers.IO) {
            Log.d("Debug", "Update started on $position")
            val status = getAccountValues(address) ?: return@launch
            if(status.balance.amount != AccountsKeeper.getBalance(position).amount) {
                AccountsKeeper.updateBalance(position, status.balance)
                AccountsKeeper.store(context)
            }
            AccountsKeeper.stopAnimate(position)
            notifyItemChanged(position)
            Log.d("Debug", "Balance updated on $position, to " + status.balance)
        }
    }
    override fun onBindViewHolder(holder: AccountItemViewHolder, position: Int) {
        Log.d("Debug", "View bind:$position")
        holder.tick.visibility = if(tracker?.isSelected(position.toLong()) == true) View.VISIBLE else View.GONE
        val account: Account = AccountsKeeper.getAccount(position)
        val icon = Identicon.fromValue(account.address, size = identiconResolution, style = IdenticonStyle(padding = 0F))
        val targetBitmap = Bitmap.createBitmap(identiconResolution, identiconResolution, Bitmap.Config.ARGB_8888)
        icon.drawToBitmap(targetBitmap)
        holder.identicon.setImageBitmap(targetBitmap)

        holder.addressLabel.text = account.address
        holder.balanceLabel.text = getBalanceString(AccountsKeeper.getBalance(position))


        holder.progressBar.visibility = if(AccountsKeeper.isAnimated(position)) View.VISIBLE else View.INVISIBLE
        holder.updateButton.visibility = if(AccountsKeeper.isAnimated(position)) View.INVISIBLE else View.VISIBLE


        holder.updateButton.setOnClickListener{
            updateBalance(account.address, position)
        }

        holder.inner.setOnClickListener {
            Log.d("Debug", "Clicked $position")
            activity.switchToExplorer(position)
        }
    }
}