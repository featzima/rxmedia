package com.featzima.rxmedia.extensions

import java.nio.ByteBuffer

fun ByteBuffer.transferToAsMuchAsPossible(destinationBuffer: ByteBuffer): Int {
    val nTransfer = Math.min(destinationBuffer.remaining(), remaining())
    if (nTransfer > 0) {
        destinationBuffer.put(array(), arrayOffset() + position(), nTransfer)
        position(position() + nTransfer)
    }
    return nTransfer
}

fun ByteBuffer.exactlyCopy(): ByteBuffer {
    val copy = ByteBuffer.allocate(limit())
    copy.put(this)
    copy.position(0)
    return copy
}