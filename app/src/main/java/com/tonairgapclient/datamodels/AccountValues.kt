package com.tonairgapclient.datamodels

import org.ton.block.Coins

data class AccountValues(
    val state: State,
    val balance: Coins = Coins()
) {
    enum class State {
        ACTIVE, FROZEN, UNINIT
    }
}
