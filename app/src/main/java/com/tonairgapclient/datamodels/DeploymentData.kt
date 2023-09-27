package com.tonairgapclient.datamodels

import com.tonairgapclient.Transaction
import org.ton.bitstring.BitString
import org.ton.block.Coins

data class DeploymentData(
    override val lt: ULong,
    override val prevTxnLt: ULong,
    override val hash: BitString,
    override val prevTxnHash: BitString,
    override val fee: Coins,
    override val dateTime: ULong,
    val address: String) : TransactionData {
    override fun toProtoTransaction(): Transaction {
        val builder = super.toProtoTransaction().toBuilder()
        builder.type = TransactionData.Type.DEPLOYMENT.ordinal
        return builder.build()
    }
}
