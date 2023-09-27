package com.tonairgapclient.datamodels

import com.tonairgapclient.storage.AccountsKeeper
import com.tonairgapclient.Transaction
import org.ton.bigint.toBigInt
import org.ton.bitstring.BitString
import org.ton.block.Coins

class TransferData(
    override val lt: ULong,
    override val prevTxnLt: ULong,
    override val hash: BitString,
    override val prevTxnHash: BitString,
    override val fee: Coins,
    override val dateTime: ULong,
    private val secondAddress: String,
    private val incoming: Boolean,
    val value: Coins
) : TransactionData {
    override fun toProtoTransaction(): Transaction {
        val builder = super.toProtoTransaction().toBuilder()
        builder.secondAddress = secondAddress
        builder.incoming = incoming
        builder.ton = (value.amount.value / AccountsKeeper.BILLION.toBigInt()).toLong()
        builder.nanoton = (value.amount.value % AccountsKeeper.BILLION.toBigInt()).toLong()
        builder.type = TransactionData.Type.TRANSFER.ordinal
        return builder.build()
    }
}

