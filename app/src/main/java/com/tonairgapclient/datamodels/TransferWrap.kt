package com.tonairgapclient.datamodels

import org.ton.block.Coins

data class TransferWrap(
    val source: String,
    val dest: String,
    val comment: String?,
    val amount: Coins,
    val seqno: Long
)
