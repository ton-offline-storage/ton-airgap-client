package com.tonairgapclient.blockchain

import android.os.StrictMode
import android.util.Log
import com.tonairgapclient.datamodels.AccountValues
import com.tonairgapclient.datamodels.DeploymentData
import com.tonairgapclient.datamodels.FeeRequest
import com.tonairgapclient.datamodels.TransactionData
import com.tonairgapclient.datamodels.TransferData
import com.tonairgapclient.datamodels.TransferWrap
import com.tonairgapclient.datamodels.UnknownTransactionData
import com.tonairgapclient.utils.address
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.ton.api.liteclient.config.LiteClientConfigGlobal
import org.ton.api.tonnode.TonNodeBlockIdExt
import org.ton.bitstring.BitString
import org.ton.block.AccountActive
import org.ton.block.AccountFrozen
import org.ton.block.AccountInfo
import org.ton.block.AccountNone
import org.ton.block.AccountStatus
import org.ton.block.AddrStd
import org.ton.block.Coins
import org.ton.block.CommonMsgInfoRelaxed
import org.ton.block.ExtInMsgInfo
import org.ton.block.IntMsgInfo
import org.ton.block.Message
import org.ton.block.MessageRelaxed
import org.ton.block.MsgAddressInt
import org.ton.block.VmStackTinyInt
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.crypto.encoding.base64
import org.ton.hashmap.HmeRoot
import org.ton.hashmap.HmnLeaf
import org.ton.lite.api.exception.LiteServerException
import org.ton.lite.api.liteserver.LiteServerAccountId
import org.ton.lite.api.liteserver.functions.LiteServerSendMessage
import org.ton.lite.client.LiteClient
import org.ton.lite.client.internal.FullAccountState
import org.ton.lite.client.internal.TransactionId
import org.ton.lite.client.internal.TransactionInfo
import org.ton.tl.asByteString
import org.ton.tlb.constructor.AnyTlbConstructor
import org.ton.tlb.loadTlb
import java.net.URL
import kotlin.coroutines.CoroutineContext

object TonlibController : CoroutineScope {
    private const val MAINNET_ID = 0
    private const val GET_DATA_TRIES = 5
    private const val TRANSACTIONS_CHUNK = 8
    private const val SIGNATURE_BYTE_SIZE = 64
    private const val DEPLOYMENT_BODY_BIT_SIZE = 32
    private const val BLOCK_UPDATE_TIMEOUT: Long = 5000L
    private const val TON_RATE_UPDATE_TIMEOUT: Long = 60000L
    override val coroutineContext: CoroutineContext = Dispatchers.IO
    private val jsonFormat = Json {ignoreUnknownKeys = true}
    private const val LITESERVERS_CONFIG_URL: String = "https://ton.org/global-config.json"
    const val TONCENTER: String = "https://toncenter.com/api/v2/jsonRPC"
    private const val TONAPI_RATES_URL: String = "https://tonapi.io/v2/rates?tokens=ton&currencies=ton,usd,rub"
    private lateinit var liteClient: LiteClient
    private var numLiteServers = 0
    private var clientInited = false
    private lateinit var lastBlockId: TonNodeBlockIdExt
    private var tonUSDRate: Double? = null
    private const val BILLION: Double = 1e9
    fun initClient() : Boolean {
        if(clientInited) {
            return true
        }
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        try {
            val config = jsonFormat.decodeFromString<LiteClientConfigGlobal>(
                URL(LITESERVERS_CONFIG_URL).readText()
            )
            numLiteServers = config.liteServers.size
            liteClient = LiteClient(Dispatchers.IO, config)
        } catch (e: Exception) {
            return false
        }
        clientInited = true
        launch {
            while (true) {
                delay(BLOCK_UPDATE_TIMEOUT)
                updateLastBlockId()
                Log.d("DebugU", "Block updated")
            }
        }
        launch {
            while (true) {
                updateTonRate()
                delay(TON_RATE_UPDATE_TIMEOUT)
            }
        }
        return true
    }
    private fun updateTonRate() {
        val rateData = JSONObject(URL(TONAPI_RATES_URL).readText())
        tonUSDRate = rateData.getJSONObject("rates").getJSONObject("TON").getJSONObject("prices").getDouble("USD")
    }
    fun getTonPriceString(amount: Coins): String {
        val usdValue = amount.amount.value.toDouble() / BILLION * (tonUSDRate ?: return "")
        return "($" + String.format("%.3f", usdValue) + ")"
    }
    fun validateAddress(address: String) : Boolean {
        return try {
            AddrStd(address)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }
    private fun addressToString(address: MsgAddressInt): String {
        return MsgAddressInt.toString(address, bounceable = false)
    }
    suspend fun sendBytes(bytes : ByteArray) : Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("Debug", "Sending Status: " + liteClient.liteApi(LiteServerSendMessage(bytes.asByteString())).status)
        } catch (e: Exception) {
            Log.d("Debug", "Sending error: " + e.message)
            return@withContext false
        }
        true
    }
    private fun extractMessageFromBoc(bytes: ByteArray) : Message<Cell> {
        val boc = BagOfCells(bytes)
        Log.d("Debug", "ROOTS: " + boc.roots.size)
        val cell = boc.roots.first()
        Log.d("Debug", "ROOT LEN: " + cell.bits.size)
        Log.d("Debug", "ROOT REFS: " + cell.refs)
        return cell.parse {
            loadTlb(Message.Any)
        }
    }
    fun extractAddressFromInitBoc(bytes: ByteArray) : String? {
        try {
            val message = extractMessageFromBoc(bytes)
            val stateInit = message.init.value?.x ?: message.init.value?.y?.value ?: return null
            val address = address(MAINNET_ID, stateInit)
            return AddrStd.toString(address, bounceable = false)
        } catch (e: Exception) {
            return null
        }
    }
    fun extractDataFromTransferBoc(bytes: ByteArray) : TransferWrap? {
        try {
            val message = extractMessageFromBoc(bytes)
            val sourceAddress = addressToString((message.info as ExtInMsgInfo).dest)
            val body = message.body.x ?: message.body.y?.value ?: return null
            if(body.bits.size <= DEPLOYMENT_BODY_BIT_SIZE) {
                Log.d("Debug", "Low bits number")
                return null
            }
            Log.d("Debug", "BODY BIT length: " + body.bits.size)
            Log.d("Debug", "Body refs: " + body.refs)
            val signingMessage = body.parse {
                skipBits(SIGNATURE_BYTE_SIZE * 8)
                Cell(loadBits(bits.size - bitsPosition), *loadRefs(refs.size).toTypedArray())
            }
            Log.d("Debug", "Bit Length: " + signingMessage.bits.size)
            Log.d("Debug", "REFS: " + signingMessage.refs)
            val seqno = signingMessage.parse {
                skipBits(64)
                val res = loadUInt(32)
                skipBits(bits.size - bitsPosition)
                res.toLong()
            }
            val cell = signingMessage.refs.first()
            if(cell.isEmpty()) {
                Log.d("Debug", "Signing message ref is empty")
            }
            Log.d("Debug", "Cell is: $cell")
            val messageRelaxed = cell.parse{
                loadTlb(MessageRelaxed.tlbCodec(AnyTlbConstructor))
            }
            val info = messageRelaxed.info as CommonMsgInfoRelaxed.IntMsgInfoRelaxed
            val payload = messageRelaxed.body.x ?: message.body.y?.value
            if(payload == null) {
                Log.d("Debug", "Null payload")
            }
            if (payload != null) {
                if(payload.isEmpty()) {
                    Log.d("Debug", "Empty payload")
                }
            }
            var commentBits = BitString()
            var currentCell = payload
            while(currentCell != null) {
                commentBits += currentCell.bits
                currentCell = currentCell.refs.firstOrNull()
            }
            return TransferWrap(sourceAddress, addressToString(info.dest),
                commentBits.slice(32).toByteArray().decodeToString(),
                info.value.coins, seqno)
        } catch(e: Exception) {
            Log.d("Debug", "Exception: " + e.message)
            Log.d("Debug", "Stack trace: " + e.stackTraceToString())
            e.printStackTrace()
            return null
        }
    }
    private fun extractFeeRequestFromBoc(bytes: ByteArray, isDeploy: Boolean): FeeRequest? {
        val message = extractMessageFromBoc(bytes)
        val body = message.body.x ?: message.body.y?.value ?: return null
        if(isDeploy) {
            val address = extractAddressFromInitBoc(bytes) ?: return null
            val stateInitEither = message.init.value ?: return null
            val stateInit = stateInitEither.x ?: stateInitEither.y?.value ?: return null
            val code = stateInit.code.value?.value ?: return null
            val data = stateInit.data.value?.value ?: return null
            return FeeRequest(address,
                base64(BagOfCells(body).toByteArray()),
                base64((BagOfCells(code)).toByteArray()),
                base64(BagOfCells(data).toByteArray())
            )
        } else {
            val sourceAddress = (message.info as? ExtInMsgInfo)?.dest ?: return null
            val address = addressToString(sourceAddress)
            return FeeRequest(address,
                base64(BagOfCells(body).toByteArray()),
                "",
                ""
            )
        }
    }
    fun makeFeeRequestObj(bytes: ByteArray, isDeployment: Boolean): JSONObject? {
        val params = extractFeeRequestFromBoc(bytes, isDeployment) ?: return null
        val paramsObj = JSONObject(mapOf("address" to params.address,
            "body" to params.body,
            "init_code" to params.code,
            "init_data" to params.data))
        val requestObj = JSONObject()
        requestObj.put("id",1)
        requestObj.put("jsonrpc", "2.0")
        requestObj.put("method", "estimateFee")
        requestObj.put("params", paramsObj)
        return requestObj
    }
    private suspend fun getSeqnoAttempt(address: String, blockId: TonNodeBlockIdExt? = null): Long?  {
        return try {
            val addrStd = AddrStd(address)
            val liteServerAccountId = LiteServerAccountId(addrStd.workchainId, addrStd.address)
            val resultList = if(blockId != null)
                liteClient.runSmcMethod(liteServerAccountId, blockId, "seqno").stack
                else liteClient.runSmcMethod(liteServerAccountId, "seqno").stack

            (resultList.first() as VmStackTinyInt).value
        } catch (e: Exception) {
            Log.d("Debug", "Caught in get seqno attempt: " + e.message)
            null
        }
    }
    suspend fun getSeqno(address: String, blockId: TonNodeBlockIdExt? = null): Long? = withContext(Dispatchers.IO) {
        var tries = GET_DATA_TRIES
        var seqno: Long? = null
        while(seqno == null && tries > 0) {
            seqno = getSeqnoAttempt(address, blockId)
            --tries
        }
        if (seqno != null) {
            Log.d("Debug", "Needed " + (GET_DATA_TRIES - tries) + " seqno tries")
        } else {
            Log.d("Debug", "Couldn't get seqno")
        }
        seqno
    }
    suspend fun getCachedLastBlockId(): TonNodeBlockIdExt {
        return if(!this@TonlibController::lastBlockId.isInitialized) {
            getUpdatedLastBlockId()
        } else lastBlockId
    }
    private suspend fun getUpdatedLastBlockId(): TonNodeBlockIdExt {
        updateLastBlockId()
        return getCachedLastBlockId()
    }
    suspend fun updateLastBlockId(): Boolean = withContext(Dispatchers.IO) {
        val currentBlockId = liteClient.getLastBlockId()
        if(!this@TonlibController::lastBlockId.isInitialized || lastBlockId != currentBlockId) {
            lastBlockId = currentBlockId
            true
        } else {
            false
        }
    }
    suspend fun getFullAccountState(address: String): FullAccountState? = withContext(Dispatchers.IO) {
        getFullAccountState(address, getUpdatedLastBlockId())
    }
    suspend fun getFullAccountState(address: String, lastBlockId: TonNodeBlockIdExt): FullAccountState? = withContext(Dispatchers.IO) {
        var result: FullAccountState? = null
        var tries = GET_DATA_TRIES
        while(result == null && tries > 0) {
            Log.d("Debug", "trying for $address")
            try {
                result = liteClient.getAccountState(AddrStd(address), lastBlockId)
            } catch (e: Exception) {
                Log.d("Debug", "get Full State attempt fail: " + e.message)
                e.printStackTrace()
                if(e is LiteServerException) {
                    Log.d("Debug", "LiteServer Exception")
                }
            }
            --tries
        }
        if (result != null) {
            Log.d("Debug", "Needed " + (GET_DATA_TRIES - tries) + " account storage tries for $address")
        } else {
            Log.d("Debug", "Couldn't get account storage")
        }
        result
    }
    fun getAccountValues(fullState: FullAccountState): AccountValues? {
        return when(val account = fullState.account.value) {
            is AccountNone -> AccountValues(AccountValues.State.UNINIT)
            is AccountInfo -> {
                val state = when (account.storage.state) {
                    is AccountActive -> AccountValues.State.ACTIVE
                    is AccountFrozen -> AccountValues.State.FROZEN
                    else -> AccountValues.State.UNINIT
                }
                AccountValues(state, account.storage.balance.coins)
            }
            else -> null
        }
    }
    suspend fun getAccountValues(address: String): AccountValues? = withContext(Dispatchers.IO) {
        val fullState = getFullAccountState(address) ?: return@withContext null
        getAccountValues(fullState)
    }
    private fun extractInternalTransfer(lt: ULong, prevTxnLt: ULong, hash: BitString,
                                        prevTxnHash: BitString, fee: Coins, dateTime: ULong,
                                        msgInfo: IntMsgInfo, mainAddress: String): TransferData {
        val src = addressToString(msgInfo.src)
        val dest = addressToString(msgInfo.dest)
        val value = msgInfo.value.coins
        return when(src == mainAddress) {
            false -> TransferData(lt, prevTxnLt, hash, prevTxnHash, fee, dateTime, src, true, value)
            true -> TransferData(lt, prevTxnLt, hash, prevTxnHash, fee, dateTime, dest, false, value)
        }
    }
    private fun extractTransactionData(transactionInfo: TransactionInfo, mainAddress: String): TransactionData {
        val transaction = transactionInfo.transaction.value
        val lt = transaction.lt
        val hash = transactionInfo.id.hash
        val prevTxnLt = transaction.prevTransLt
        val prevTxnHash = transaction.prevTransHash
        val fee = transaction.totalFees.coins
        val dateTime = transaction.now.toULong()
        val unknownTxn = UnknownTransactionData(lt, prevTxnLt, hash, prevTxnHash, fee, dateTime)
        val inMsg = transaction.r1.value.inMsg.get()?.value ?: return unknownTxn

        return when(val msgInfo = inMsg.info) {
            is IntMsgInfo -> {
                extractInternalTransfer(lt, prevTxnLt, hash, prevTxnHash, fee, dateTime, msgInfo, mainAddress)
            }
            is ExtInMsgInfo -> {
                if(transaction.origStatus == AccountStatus.UNINIT &&
                    transaction.endStatus == AccountStatus.ACTIVE &&
                    inMsg.init.get() != null && addressToString(msgInfo.dest) == mainAddress) {
                    DeploymentData(lt, prevTxnLt, hash, prevTxnHash, fee, dateTime, mainAddress)
                } else if(transaction.outMsgCnt == 1) {
                    val outMsgInfo = ((transaction.r1.value.outMsgs as? HmeRoot)?.
                                        root?.value?.node as? HmnLeaf)?.value?.value?.info as? IntMsgInfo
                    if(outMsgInfo != null) extractInternalTransfer(lt, prevTxnLt, hash,
                        prevTxnHash, fee, dateTime, outMsgInfo, mainAddress)
                    else unknownTxn
                } else unknownTxn
            }
            else -> unknownTxn
        }
    }
    suspend fun getTransactions(accountAddress: MsgAddressInt,
                                fromTransactionId: TransactionId,
                                count: Int = TRANSACTIONS_CHUNK
    ): List<TransactionData>? = withContext(Dispatchers.IO) {
        var result: List<TransactionData>? = null
        var tries = numLiteServers
        while(result == null && tries > 0) {
            Log.d("Debug", "Try get transactions")
            try {
                result = liteClient.getTransactions(accountAddress, fromTransactionId, count).map { txnInfo ->
                    extractTransactionData(txnInfo, addressToString(accountAddress))
                }
            } catch (e: Exception) {
                Log.d("Debug", "get Transactions fail: " + e.message)
                e.printStackTrace()
                if (e is LiteServerException) {
                    Log.d("Debug", "LiteServer Exception")
                }
            }
            --tries
        }
        if (result != null) {
            Log.d("Debug", "Needed " + (numLiteServers - tries) + " txn tries for: " + addressToString(accountAddress))
        } else {
            Log.d("Debug", "Couldn't get transactions")
        }
        result
    }
}