package com.featzima.rxmedia.i

import android.graphics.Bitmap
import org.reactivestreams.Subscriber

interface IRxVideoCodec : IRxCodec {
    fun input(): Subscriber<CodecEvent<Bitmap>>
}