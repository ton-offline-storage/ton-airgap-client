package com.tonairgapclient.scrolling

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tonairgapclient.activities.AccountExplorerActivity
import com.tonairgapclient.storage.AccountsKeeper
import com.tonairgapclient.R
import com.tonairgapclient.datamodels.TransactionData
import com.tonairgapclient.utils.trimZeros
import org.ton.block.Coins

class TransactionItemAdapter(private val accountIndex: Int,
                             private val activity: AccountExplorerActivity,
                             private val clipboard: ClipboardManager): ItemAdapter<RecyclerView.ViewHolder>() {
    class IncomingViewHolder(view: View): RecyclerView.ViewHolder(view) {
        private val addressLabel: TextView = view.findViewById(R.id.address_label)
        private val valueLabel: TextView = view.findViewById(R.id.value_label)
        private val feeLabel: TextView = view.findViewById(R.id.fee_label)
        private val dateTimeLabel: TextView = view.findViewById(R.id.date_time_label)
        fun bind(accountIndex: Int, position: Int, activity: AccountExplorerActivity,
                 clipboard: ClipboardManager, context: Context = activity) {
            Log.d("Debug", "Incoming binded at $position")
            val txn = AccountsKeeper.getAccountTxn(accountIndex, position)
            addressLabel.text = txn.secondAddress
            valueLabel.text = context.resources.getString(R.string.pos_value, trimZeros(
                AccountsKeeper.getTransactionValue(accountIndex, position).toString())
            )
            feeLabel.text = trimZeros(Coins(txn.feeNanoton).toString())
            dateTimeLabel.text = AccountsKeeper.formatDateTime(txn.dateTime)
            addressLabel.setOnClickListener{
                val clip = ClipData.newPlainText("Address", addressLabel.text)
                clipboard.setPrimaryClip(clip)
                activity.showAddressCopiedSnackBar()
            }
        }
    }
    class OutgoingViewHolder(view: View): RecyclerView.ViewHolder(view) {
        private val addressLabel: TextView = view.findViewById(R.id.address_label)
        private val valueLabel: TextView = view.findViewById(R.id.value_label)
        private val feeLabel: TextView = view.findViewById(R.id.fee_label)
        private val dateTimeLabel: TextView = view.findViewById(R.id.date_time_label)
        fun bind(accountIndex: Int, position: Int, activity: AccountExplorerActivity,
                 clipboard: ClipboardManager, context: Context = activity) {
            Log.d("Debug", "Outgoing binded at $position")
            val txn = AccountsKeeper.getAccountTxn(accountIndex, position)
            addressLabel.text = txn.secondAddress
            valueLabel.text = context.resources.getString(R.string.neg_value, trimZeros(
                AccountsKeeper.getTransactionValue(accountIndex, position).toString())
            )
            feeLabel.text = trimZeros(Coins(txn.feeNanoton).toString())
            dateTimeLabel.text = AccountsKeeper.formatDateTime(txn.dateTime)
            addressLabel.setOnClickListener{
                val clip = ClipData.newPlainText("Address", addressLabel.text)
                clipboard.setPrimaryClip(clip)
                activity.showAddressCopiedSnackBar()
            }
        }
    }
    class DeploymentViewHolder(view: View): RecyclerView.ViewHolder(view) {
        private val feeLabel: TextView = view.findViewById(R.id.fee_label)
        private val dateTimeLabel: TextView = view.findViewById(R.id.date_time_label)
        fun bind(accountIndex: Int, position: Int) {
            Log.d("Debug", "Deployment binded at $position")
            val txn = AccountsKeeper.getAccountTxn(accountIndex, position)
            feeLabel.text = trimZeros(Coins(txn.feeNanoton).toString())
            dateTimeLabel.text = AccountsKeeper.formatDateTime(txn.dateTime)
        }
    }
    class UnknownViewHolder(view: View): RecyclerView.ViewHolder(view) {
        private val feeLabel: TextView = view.findViewById(R.id.fee_label)
        private val dateTimeLabel: TextView = view.findViewById(R.id.date_time_label)
        fun bind(accountIndex: Int, position: Int) {
            Log.d("Debug", "Unknown binded at $position")
            val txn = AccountsKeeper.getAccountTxn(accountIndex, position)
            feeLabel.text = trimZeros(Coins(txn.feeNanoton).toString())
            dateTimeLabel.text = AccountsKeeper.formatDateTime(txn.dateTime)
        }
    }
    class LoadingViewHolder(view: View): RecyclerView.ViewHolder(view) {
        fun bind(position: Int) {
            Log.d("Debug", "Loader binded at $position")
        }
    }
    enum class ViewType {
        INCOMING, OUTGOING, DEPLOYMENT, UNKNOWN, LOADING
    }
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RecyclerView.ViewHolder {
        return when(viewType) {
            ViewType.INCOMING.ordinal -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.incoming_transaction_card, parent, false)
                IncomingViewHolder(view)
            }
            ViewType.OUTGOING.ordinal -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.outgoing_transaction_card, parent, false)
                OutgoingViewHolder(view)
            }
            ViewType.DEPLOYMENT.ordinal -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.deployment_transaction_card, parent, false)
                DeploymentViewHolder(view)
            }
            ViewType.UNKNOWN.ordinal -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.unknown_transaction_card, parent, false)
                UnknownViewHolder(view)
            }
            ViewType.LOADING.ordinal -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.loading_card, parent, false)
                LoadingViewHolder(view)
            }
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }
    override fun getItemViewType(position: Int): Int {
        if(AccountsKeeper.needLoading(accountIndex) &&
            position == AccountsKeeper.getAccountTxsNum(accountIndex)) return ViewType.LOADING.ordinal
        val transaction = AccountsKeeper.getAccountTxn(accountIndex, position)
        return when (transaction.type) {
            TransactionData.Type.TRANSFER.ordinal -> {
                if (transaction.incoming) ViewType.INCOMING.ordinal else ViewType.OUTGOING.ordinal
            }
            TransactionData.Type.DEPLOYMENT.ordinal -> ViewType.DEPLOYMENT.ordinal
            TransactionData.Type.UNKNOWN.ordinal -> ViewType.UNKNOWN.ordinal
            else -> throw IllegalArgumentException("Invalid type of data at: $position")
        }
    }
    override fun getItemCount() = AccountsKeeper.getAccountTxsNumWithLoader(accountIndex)

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when(holder) {
            is IncomingViewHolder -> holder.bind(accountIndex, position, activity, clipboard)
            is OutgoingViewHolder -> holder.bind(accountIndex, position, activity, clipboard)
            is DeploymentViewHolder -> holder.bind(accountIndex, position)
            is UnknownViewHolder -> holder.bind(accountIndex, position)
            is LoadingViewHolder -> holder.bind(position)
            else -> throw IllegalArgumentException("Wrong ViewHolder type")
        }
    }

}