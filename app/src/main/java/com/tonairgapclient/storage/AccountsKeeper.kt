package com.tonairgapclient.storage

import android.content.Context
import android.content.res.Resources
import android.util.Log
import androidx.core.os.ConfigurationCompat
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import androidx.recyclerview.widget.RecyclerView
import com.android.volley.RequestQueue
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.RequestFuture
import com.android.volley.toolbox.Volley
import com.google.protobuf.ByteString
import com.tonairgapclient.Account
import com.tonairgapclient.AccountTransactions
import com.tonairgapclient.Accounts
import com.tonairgapclient.Transaction
import com.tonairgapclient.TransactionsDataset
import com.tonairgapclient.blockchain.TonlibController
import com.tonairgapclient.datamodels.AccountValues
import com.tonairgapclient.datamodels.TransactionData
import com.tonairgapclient.scrolling.AccountItemAdapter
import com.tonairgapclient.scrolling.ItemAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.ton.bigint.toBigInt
import org.ton.block.Coins
import java.lang.Integer.min
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext


object AccountsKeeper : CoroutineScope {
    const val BILLION = 1000000000
    private const val NUM_UPDATERS = 3
    private const val UPDATERS_TIMEOUT: Long = 6000L
    private const val DEPLOYER_TIMEOUT: Long = 12000L
    override val coroutineContext: CoroutineContext = Dispatchers.IO
    private val Context.accountsDataStore: DataStore<Accounts> by dataStore(
        fileName = "accounts.pb",
        serializer = AccountsSerializer
    )
    private val Context.transactionsDataStore: DataStore<TransactionsDataset> by dataStore(
        fileName = "transactions.pb",
        serializer = TransactionsDatasetSerializer
    )
    private var inited: Boolean = false
    private lateinit var accounts: Accounts
    private lateinit var isAnimated: MutableList<Boolean>
    private lateinit var transactionsDataset: TransactionsDataset
    private var updaters = arrayOfNulls<Job>(NUM_UPDATERS)
    private var deployer: Job? = null
    private lateinit var feeRequestQueue: RequestQueue
    var viewsAdapter: RecyclerView.Adapter<AccountItemAdapter.AccountItemViewHolder>? = null
    private val dateTimeFormatter = SimpleDateFormat("HH:mm:ss  dd.MM.yyyy", ConfigurationCompat.getLocales(
        Resources.getSystem().configuration)[0])
    @JvmStatic
    fun formatDateTime(unixSeconds: Long): String {
        return dateTimeFormatter.format(Date(unixSeconds * 1000L))
    }
    @JvmStatic
    fun initKeeper(context: Context) {
        if(inited) return
        load(context)
        startUpdaters(context)
        if(hasNeedingDeployment()) startDeployer(context)
        inited = true
    }
    @JvmStatic
    private fun startUpdater(mod: Int, context: Context) {
        updaters[mod] = launch {
            Log.d("DebugU", "Updater mod: $mod started")
            var index = mod
            delay(UPDATERS_TIMEOUT * mod)
            while(true) {
                delay(UPDATERS_TIMEOUT)
                Log.d("DebugU", "Updater mod: $mod is working...")
                if(index >= accounts.accountsCount) index = mod
                if(index >= accounts.accountsCount) continue
                val address = accounts.getAccounts(index).address
                val state = TonlibController.getFullAccountState(address,
                    TonlibController.getCachedLastBlockId()) ?: continue
                val values = TonlibController.getAccountValues(state) ?: continue
                Log.d("DebugU", "Updater mod: $mod got values")
                var updated = false
                val currentIndex = findAccount(address) ?: continue
                if (values.balance != getBalance(currentIndex)) {
                    updateBalance(currentIndex, values.balance)
                    viewsAdapter?.notifyItemChanged(currentIndex)
                    updated = true
                }
                if(updated) store(context)
                Log.d("DebugU", "Updater mod: $mod saved values")
                index += min(NUM_UPDATERS, accounts.accountsCount)
            }
        }
    }
    @JvmStatic
    private fun stopUpdater(mod: Int) {
        Log.d("DebugU", "Stopping updater mod $mod")
        updaters[mod]?.cancel()
        updaters[mod] = null
    }
    @JvmStatic
    private fun startUpdaters(context: Context) {
        for(i in 0 until min(NUM_UPDATERS, accounts.accountsCount)) {
            startUpdater(i, context)
        }
    }
    @JvmStatic
    private fun makeDeploymentFeeRequest(bytes: ByteArray): Coins? {
        Log.d("DebugD", "Deployment fee request")
        val requestObj = TonlibController.makeFeeRequestObj(bytes, true) ?: return null
        val requestFuture: RequestFuture<JSONObject> = RequestFuture.newFuture()
        feeRequestQueue.add(object : JsonObjectRequest(
            Method.POST, TonlibController.TONCENTER, requestObj,
            requestFuture, requestFuture) {
            override fun getHeaders(): MutableMap<String, String> {
                return mutableMapOf("Content-Type" to "application/json")
            }
        })
        try {
            val response = requestFuture.get(4, TimeUnit.SECONDS)
            if(response.getBoolean("ok")) {
                val fees = response.getJSONObject("result").getJSONObject("source_fees")
                return Coins(fees.getLong("in_fwd_fee").toBigInt() +
                            fees.getLong("storage_fee").toBigInt() +
                            fees.getLong("gas_fee").toBigInt() +
                            fees.getLong("fwd_fee").toBigInt())
            } else {
                Log.d("Debug", "Toncenter request error 2: " + response.getJSONObject("error").toString())
            }
        } catch (e: Exception) {
            Log.d("Debug", "Toncenter request error: " + e.stackTraceToString())
        }
        return null
    }
    @JvmStatic
    private suspend fun sendDeployment(address: String, bytes: ByteArray, fee: Coins) {
        val values = TonlibController.getAccountValues(address) ?: return
        if(values.state != AccountValues.State.UNINIT) {
            Log.d("DebugD", "Already init, doesn't need deployment")
            val index = findAccount(address) ?: return
            noNeedDeployment(index)
        }
        if(values.balance.amount.value < fee.amount.value) return
        Log.d("DebugD", "Sending Postponed Deployment")
        val res = TonlibController.sendBytes(bytes)
        Log.d("DebugD", "Postponed deployment sending result: $res")
        if(res) {
            val index = findAccount(address) ?: return
            noNeedDeployment(index)
        }
    }
    @JvmStatic
    fun startDeployer(context: Context) {
        if(deployer?.isActive == true || !hasNeedingDeployment()) return
        deployer = launch {
            feeRequestQueue = Volley.newRequestQueue(context)
            while (true) {
                delay(DEPLOYER_TIMEOUT)
                val addresses = accounts.accountsList.mapNotNull { account ->
                    account.address.takeIf { account.needDeployment }
                }
                if(addresses.isEmpty()) {
                    Log.d("DebugD", "Deployed Everything!")
                    return@launch
                }
                for (address in addresses) {
                    Log.d("DebugD", "Deploying address $address")
                    var index = findAccount(address) ?: continue
                    val feeValue = makeDeploymentFeeRequest(accounts.getAccounts(index).deploymentBytes.toByteArray()) ?: continue
                    Log.d("DebugD", "Got fee on $address")
                    index = findAccount(address) ?: continue
                    sendDeployment(address, accounts.getAccounts(index).deploymentBytes.toByteArray(), feeValue)
                    Log.d("DebugD", "Tried sending on $address")
                }
            }
        }
    }
    @JvmStatic
    private fun load(context: Context) = runBlocking {
        accounts = context.accountsDataStore.data.first()
        transactionsDataset = context.transactionsDataStore.data.first()
        isAnimated = MutableList(accounts.accountsCount) {false}
        Log.d("Debug", "Keeper loaded " + accounts.accountsCount +
                " accounts and " + transactionsDataset.datasetCount + " transaction lists")
    }
    @JvmStatic
    suspend fun store(context: Context) = withContext(Dispatchers.IO) {
        context.accountsDataStore.updateData {
            accounts
        }
        context.transactionsDataStore.updateData {
            transactionsDataset
        }
    }
    @JvmStatic
    fun addAccount(account: Account, context: Context) {
        isAnimated.add(false)
        transactionsDataset = transactionsDataset.toBuilder().addDataset(AccountTransactions.getDefaultInstance()).build()
        accounts = accounts.toBuilder().addAccounts(account).build()
        if(accounts.accountsCount <= NUM_UPDATERS) {
            startUpdater(accounts.accountsCount - 1, context)
        }
    }
    @JvmStatic
    fun addAccount(address: String, context: Context, ton: Long = 0, nanoton: Long = 0, seqno: Long = 0) {
        addAccount(Account.newBuilder().setAddress(address).setTon(ton).setNanoton(nanoton).setSeqno(seqno).build(), context)
    }
    @JvmStatic
    fun removeAccount(index: Long) {
        isAnimated.removeAt(index.toInt())
        transactionsDataset = transactionsDataset.toBuilder().removeDataset(index.toInt()).build()
        accounts = accounts.toBuilder().removeAccounts(index.toInt()).build()
        if(accounts.accountsCount < NUM_UPDATERS) {
            stopUpdater(accounts.accountsCount)
        }
    }
    @JvmStatic
    fun getAccount(index: Int): Account = accounts.getAccounts(index)
    @JvmStatic
    fun getAccountTxsNum(index: Int): Int = transactionsDataset.getDataset(index).transactionsCount
    @JvmStatic
    fun getAccountTxs(index: Int): List<Transaction> = transactionsDataset.getDataset(index).transactionsList
    @JvmStatic
    fun getAccountTxn(accountIndex: Int, index: Int): Transaction =
        transactionsDataset.getDataset(accountIndex).getTransactions(index)
    @JvmStatic
    @JvmName("addNewTxsImpl")
    private fun addNewTxs(accountIndex: Int, transactions: List<Transaction>, adapter: ItemAdapter<RecyclerView.ViewHolder>) {
        val needLoadingOrig: Boolean = needLoading(accountIndex)
        if(transactions.isEmpty()) return
        var newTxs = transactions
        var oldTxs = transactionsDataset.getDataset(accountIndex)
        var removedOld = 0

        if(oldTxs.transactionsCount > 0) {
            val firstFromOldInNewIndex = newTxs.indexOfFirst { txn ->
                txn.lt == oldTxs.transactionsList.first().lt &&
                        txn.hash == oldTxs.transactionsList.first().hash
            }
            if(firstFromOldInNewIndex == -1) {
                if(!(newTxs.last().prevTxnLt == oldTxs.transactionsList.first().lt &&
                        newTxs.last().prevTxnHash == oldTxs.transactionsList.first().hash)) {
                    removedOld = oldTxs.transactionsCount
                    oldTxs = oldTxs.toBuilder().clearTransactions().build()
                }
            } else {
                if(newTxs.any {txn ->
                        txn.lt == oldTxs.transactionsList.last().prevTxnLt &&
                                txn.hash == oldTxs.transactionsList.last().prevTxnHash
                    }) {
                    removedOld = oldTxs.transactionsCount
                    oldTxs = oldTxs.toBuilder().clearTransactions().build()
                } else {
                    newTxs = newTxs.subList(0, firstFromOldInNewIndex)
                }
            }
        }
        transactionsDataset = transactionsDataset.toBuilder().setDataset(accountIndex,
            AccountTransactions.newBuilder().addAllTransactions(newTxs).
            addAllTransactions(oldTxs.transactionsList)).build()
        val needLoadingEnd: Boolean = needLoading(accountIndex)

        adapter.notifyItemRangeRemoved(0, removedOld + (
                if(!needLoadingEnd && needLoadingOrig) 1 else 0))
        adapter.notifyItemRangeInserted(0, newTxs.size)
        if(needLoadingEnd && !needLoadingOrig)
            adapter.notifyItemInserted(getAccountTxsNum(accountIndex))
    }
    @JvmStatic
    fun addNewTxs(accountIndex: Int, transactions: List<TransactionData>,
                  adapter: ItemAdapter<RecyclerView.ViewHolder>) {
        return addNewTxs(accountIndex, transactions.map {txn ->
            txn.toProtoTransaction()
        }, adapter)
    }
    @JvmStatic
    @JvmName("addOldTxsImpl")
    private fun addOldTxs(accountIndex: Int, transactions: List<Transaction>, adapter: ItemAdapter<RecyclerView.ViewHolder>) {
        val needLoadingOrig: Boolean = needLoading(accountIndex)
        val origTxsNum: Int = getAccountTxsNum(accountIndex)
        transactionsDataset = transactionsDataset.toBuilder().setDataset(accountIndex,
            transactionsDataset.getDataset(accountIndex).toBuilder().addAllTransactions(transactions)).build()
        val needLoadingEnd: Boolean = needLoading(accountIndex)
        if(!needLoadingEnd && needLoadingOrig) adapter.notifyItemRemoved(origTxsNum)
        adapter.notifyItemRangeInserted(origTxsNum, transactions.size)
    }
    @JvmStatic
    fun addOldTxs(accountIndex: Int, transactions: List<TransactionData>, adapter: ItemAdapter<RecyclerView.ViewHolder>) {
        return addOldTxs(accountIndex, transactions.map {txn ->
            txn.toProtoTransaction()
        }, adapter)
    }
    @JvmStatic
    fun needLoading(accountIndex: Int): Boolean {
        return (getAccountTxsNum(accountIndex) > 0 && getAccountTxs(accountIndex).last().prevTxnLt != 0L)
    }
    @JvmStatic
    fun getAccountTxsNumWithLoader(accountIndex: Int): Int {
        return getAccountTxsNum(accountIndex) + (if(needLoading(accountIndex)) 1 else 0)
    }
    @JvmStatic
    fun isAnimated(index: Int) : Boolean = isAnimated[index]
    @JvmStatic
    fun animate(index: Int) {
        isAnimated[index] = true
    }
    @JvmStatic
    fun stopAnimate(index: Int) {
        isAnimated[index] = false
    }
    @JvmStatic
    fun getTransactionValue(accountIndex: Int, index: Int): Coins {
        val transaction = getAccountTxn(accountIndex, index)
        return Coins(transaction.ton.toBigInt() * BILLION.toBigInt() + transaction.nanoton.toBigInt())
    }
    @JvmStatic
    fun getBalance(index: Int): Coins {
        val account = getAccount(index)
        return Coins(account.ton.toBigInt() * BILLION.toBigInt() + account.nanoton.toBigInt())
    }
    @JvmStatic
    fun updateBalance(index: Int, coins: Coins) {
        val ton = coins.amount.value / BILLION.toBigInt()
        val nanoton = coins.amount.value % BILLION.toBigInt()
        accounts = accounts.toBuilder().setAccounts(index, accounts.getAccounts(index).toBuilder()
            .setTon(ton.toLong()).setNanoton(nanoton.toLong()).build()).build()
    }
    @JvmStatic
    fun updateSeqno(index: Int, seqno: Long) {
        accounts = accounts.toBuilder().setAccounts(index, accounts.getAccounts(index).toBuilder()
            .setSeqno(seqno).build()).build()
    }
    @JvmStatic
    fun needDeployment(index: Int, bytes: ByteArray) {
        accounts = accounts.toBuilder().setAccounts(index, accounts.getAccounts(index).toBuilder()
            .setNeedDeployment(true).setDeploymentBytes(ByteString.copyFrom(bytes))).build()
    }
    @JvmStatic
    private fun noNeedDeployment(index: Int) {
        accounts = accounts.toBuilder().setAccounts(index, accounts.getAccounts(index).toBuilder()
            .setNeedDeployment(false).setDeploymentBytes(ByteString.EMPTY)).build()
    }
    private fun hasNeedingDeployment(): Boolean {
        return accounts.accountsList.any{account ->  account.needDeployment}
    }
    @JvmStatic
    fun findAccount(address: String): Int? {
        val index = accounts.accountsList.indexOfFirst { account ->
            account.address == address
        }
        return if(index != -1) index else null
    }
    @JvmStatic
    fun size() = accounts.accountsCount
}