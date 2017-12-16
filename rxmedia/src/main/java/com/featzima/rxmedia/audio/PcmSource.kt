package com.featzima.rxmedia.audio

import android.util.Log
import com.featzima.rxmedia.extensions.transferToAsMuchAsPossible
import com.featzima.rxmedia.extensions.waitForRequested
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import org.reactivestreams.Publisher
import java.nio.ByteBuffer

class PcmSource(
        private val sourceBuffer: ByteBuffer,
        private val chunkSize: Long) {

    val output: Publisher<ByteBuffer> = Flowable.create({ emitter ->
        while(!emitter.isCancelled && this.sourceBuffer.hasRemaining()) {
            emitter.waitForRequested()
            val buffer = ByteBuffer.allocate(chunkSize.toInt())
            this.sourceBuffer.transferToAsMuchAsPossible(buffer)
            buffer.position(0)
            emitter.onNext(buffer)
        }
        emitter.onComplete()
    }, BackpressureStrategy.BUFFER)

}