package com.tonairgapclient

import android.os.StrictMode
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.ton.api.liteclient.config.LiteClientConfigGlobal
import org.ton.block.*
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.contract.Contract
import org.ton.lite.api.liteserver.LiteServerAccountId
import org.ton.lite.client.LiteClient
import org.ton.tlb.constructor.AnyTlbConstructor
import org.ton.tlb.parse
import java.net.URL

object TonlibController {
    private const val MAINNET_ID = 0
    private const val GET_DATA_TRIES = 5
    private const val SIGNATURE_BYTE_SIZE = 64
    private const val DEPLOYMENT_BODY_BIT_SIZE = 32
    private val jsonFormat = Json {ignoreUnknownKeys = true}
    private lateinit var liteClient: LiteClient
    private var clientInited = false
    fun initClient() : Boolean {
        if(clientInited) {
            return true
        }
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        try {
            val config = jsonFormat.decodeFromString<LiteClientConfigGlobal>(
                URL("https://ton.org/global-config.json").readText()
            )
            liteClient = LiteClient(config)
        } catch (e: Exception) {
            return false
        }
        clientInited = true
        return true
    }
    fun sendBytes(bytes : ByteArray) : Boolean = runBlocking {
        try {
            Log.d("Debug", "Sending Status: " + liteClient.liteApi.sendMessage(bytes).status)
        } catch (e: Exception) {
            Log.d("Debug", "Sending error: " + e.message)
            return@runBlocking false
        }
        return@runBlocking true
    }
    private fun extractMessageFromBoc(bytes: ByteArray) : Message<Cell> {
        val boc = BagOfCells(bytes)
        val cell = boc.roots.first()
        return cell.parse(Message.tlbCodec(AnyTlbConstructor))
    }
    fun extractAddressFromInitBoc(bytes: ByteArray) : String? {
        try {
            val message = extractMessageFromBoc(bytes)
            val stateInit = message.init.value?.toPair()?.first ?: return null
            val address = Contract.address(MAINNET_ID, stateInit)
            return AddrStd.toString(address)
        } catch (e: Exception) {
            return null
        }
    }
    fun extractDataFromTransferBoc(bytes: ByteArray) : TransferData? {
        try {
            val message = extractMessageFromBoc(bytes)
            val sourceAddress = MsgAddressInt.toString((message.info as ExtInMsgInfo).dest)
            val body = message.body.toPair().first ?: return null
            if(body.bits.size <= DEPLOYMENT_BODY_BIT_SIZE) {
                return null
            }
            val signingMessage = body.parse {
                skipBits(SIGNATURE_BYTE_SIZE * 8)
                Cell(loadBits(bits.size - bitsPosition), loadRefs(refs.size))
            }
            val seqno = signingMessage.parse {
                skipBits(64)
                val res = loadUInt(32)
                skipBits(bits.size - bitsPosition)
                res.toInt()
            }
            val cell = signingMessage.refs.first()
            val messageRelaxed = cell.parse(MessageRelaxed.tlbCodec(AnyTlbConstructor))
            val info = messageRelaxed.info as CommonMsgInfoRelaxed.IntMsgInfoRelaxed
            return TransferData(sourceAddress, MsgAddressInt.toString(info.dest),
                info.value.coins, seqno)
        } catch(e: Exception) {
            return null
        }
    }
    fun waitValidation(oldSeqno: Int, address: String): Boolean = runBlocking {
        var newSeqno = oldSeqno
        withTimeoutOrNull(40000L) {
            while(newSeqno == oldSeqno && isActive) {
                newSeqno = getSeqnoAttempt(address) ?: newSeqno
            }
        }
        newSeqno > oldSeqno
    }
    private fun getSeqnoAttempt(address: String): Int? = runBlocking {
        try {
            val lastBlockId = liteClient.getLastBlockId()
            val liteServerAccountId = LiteServerAccountId(address)
            val result = liteClient.liteApi.runSmcMethod(4, lastBlockId, liteServerAccountId, "seqno")
            (result.first() as VmStackTinyInt).value.toInt()
        } catch (e: Exception) {
            Log.d("Debug", "Caught: " + e.message)
            null
        }
    }
    fun getSeqno(address: String): Int? {
        var tries = GET_DATA_TRIES
        var seqno: Int? = null
        while(seqno == null && tries > 0) {
            seqno = getSeqnoAttempt(address)
            --tries
        }
        if (seqno != null) {
            Log.d("Debug", "Needed " + (GET_DATA_TRIES - tries) + " seqno tries")
        } else {
            Log.d("Debug", "Couldn't get seqno")
        }
        return seqno
    }
    private data class AccountStorageWrap(
        val storage: AccountStorage?
    )
    private fun getAccountStorage(address: String): AccountStorageWrap? = runBlocking {
        var result: AccountStorageWrap? = null
        var tries = GET_DATA_TRIES
        while(result == null && tries > 0) {
            try {
                result = AccountStorageWrap(liteClient.getAccount(address)?.storage)
            } catch (ignored: Exception) {}
            --tries
        }
        if (result != null) {
            Log.d("Debug", "Needed " + (GET_DATA_TRIES - tries) + " account storage tries")
        } else {
            Log.d("Debug", "Couldn't get account storage")
        }
        result
    }
    fun getAccountStatus(address: String): AccountStatus? {
        val storageWrap = getAccountStorage(address) ?: return null
        val storage = storageWrap.storage
        return if(storage == null) {
            AccountStatus(AccountStatus.State.UNINIT)
        } else {
            val state = when(storage.state) {
                is AccountActive -> AccountStatus.State.ACTIVE
                is AccountFrozen -> AccountStatus.State.FROZEN
                else -> AccountStatus.State.UNINIT
            }
            AccountStatus(state, storage.balance.coins)
        }
    }
}