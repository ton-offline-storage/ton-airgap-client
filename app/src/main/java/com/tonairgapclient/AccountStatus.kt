package com.tonairgapclient

import org.ton.block.Coins

data class AccountStatus(
    val state: State,
    val balance: Coins = Coins()
) {
    enum class State {
        ACTIVE, FROZEN, UNINIT
    }
}
