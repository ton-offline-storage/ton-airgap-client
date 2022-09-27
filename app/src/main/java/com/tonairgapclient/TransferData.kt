package com.tonairgapclient

import org.ton.block.Coins

data class TransferData(
    val source: String,
    val dest: String,
    val amount: Coins,
    val seqno: Int
)
