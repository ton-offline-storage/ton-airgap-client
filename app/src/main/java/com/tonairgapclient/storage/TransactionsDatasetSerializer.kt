package com.tonairgapclient.storage

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import com.tonairgapclient.TransactionsDataset
import java.io.InputStream
import java.io.OutputStream

object TransactionsDatasetSerializer : Serializer<TransactionsDataset> {
    override val defaultValue: TransactionsDataset = TransactionsDataset.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): TransactionsDataset {
        try {
            return TransactionsDataset.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(t: TransactionsDataset, output: OutputStream) = t.writeTo(output)
}