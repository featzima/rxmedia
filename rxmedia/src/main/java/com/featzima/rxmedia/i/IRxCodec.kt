package com.featzima.rxmedia.i

import org.reactivestreams.Publisher
import java.nio.ByteBuffer

interface IRxCodec {
    fun output(): Publisher<CodecEvent<ByteBuffer>>
}