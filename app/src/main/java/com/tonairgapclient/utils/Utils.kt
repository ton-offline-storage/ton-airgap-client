package com.tonairgapclient.utils

import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import org.ton.block.AddrStd
import org.ton.block.StateInit
import org.ton.cell.CellBuilder
import org.ton.tlb.TlbCodec
import org.ton.tlb.storeTlb

fun trimZeros(number: String) : String = number.trimEnd('0').trimEnd('.')

private val stateInitCodec: TlbCodec<StateInit> by lazy {
    StateInit.tlbCodec()
}

fun address(workchainId: Int, stateInit: StateInit): AddrStd {
    val cell = CellBuilder.createCell {
        storeTlb(stateInitCodec, stateInit)
    }
    val hash = cell.hash()
    return AddrStd(workchainId, hash)
}

fun getRawBytes(result: Result): ByteArray? {
    val metadata = result.resultMetadata ?: return null
    val segments = metadata[ResultMetadataType.BYTE_SEGMENTS] ?: return null
    var bytes = ByteArray(0)
    @Suppress("UNCHECKED_CAST")
    for (seg in segments as Iterable<ByteArray>) {
        bytes += seg
    }
    return if (bytes.size >= result.text.length) bytes else null
}