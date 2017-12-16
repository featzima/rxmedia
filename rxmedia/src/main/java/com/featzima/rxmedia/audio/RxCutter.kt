package com.featzima.rxmedia.audio

import org.reactivestreams.Subscription
import java.nio.ByteBuffer

class RxCutter(
        private val cutFromUs: Long,
        private val cutToUs: Long) : RxAudioBufferProcessor() {

    private var encodedBytes: Long = 0

    override fun processBuffer(subscription: Subscription, buffer: ByteBuffer): Boolean {
        val currentUs = PcmCalculator.presentationTimeByEncodedBytes(encodedBytes)
        this.encodedBytes += buffer.remaining()
        if (currentUs in cutFromUs..cutToUs) {
            emitter.onNext(buffer)
            return true
        }
        if (currentUs > cutToUs) {
            subscription.cancel()
            emitter.onComplete()
        }
        return false
    }

}