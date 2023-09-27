package com.tonairgapclient.storage

import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream
import com.google.protobuf.InvalidProtocolBufferException
import androidx.datastore.core.CorruptionException
import com.tonairgapclient.Accounts

object AccountsSerializer : Serializer<Accounts> {
    override val defaultValue: Accounts = Accounts.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): Accounts {
        try {
            return Accounts.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(t: Accounts, output: OutputStream) = t.writeTo(output)
}