package com.featzima.rxmedia.i

import org.reactivestreams.Publisher

interface IRxCodec {
    fun output(): Publisher<CodecEvent>
}