package com.featzima.rxmedia.i

interface IRxMuxer {
    fun registerCodec(codec: IRxCodec)
}