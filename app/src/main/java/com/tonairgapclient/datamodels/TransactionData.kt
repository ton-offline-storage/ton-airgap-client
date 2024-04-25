package com.tonairgapclient.datamodels

import com.google.protobuf.ByteString
import com.tonairgapclient.Transaction
import org.ton.bitstring.BitString
import org.ton.block.Coins

sealed interface TransactionData{
    val lt: ULong
    val prevTxnLt: ULong
    val hash: BitString
    val prevTxnHash: BitString
    val fee: Coins
    val dateTime: ULong
    fun toProtoTransaction(): Transaction {
        val builder = Transaction.newBuilder()
        builder.lt = lt.toLong()
        builder.prevTxnLt = prevTxnLt.toLong()
        builder.hash = ByteString.copyFrom(hash.toByteArray())
        builder.prevTxnHash = ByteString.copyFrom(prevTxnHash.toByteArray())
        builder.feeNanoton = fee.amount.value.toLong()
        builder.dateTime = dateTime.toLong()
        return builder.build()
    }
    enum class Type {
        UNKNOWN,
        DEPLOYMENT,
        TRANSFER
    }
}
