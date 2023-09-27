package com.tonairgapclient.datamodels

data class FeeRequest(
    val address: String,
    val body: String,
    val code: String,
    val data: String
)
