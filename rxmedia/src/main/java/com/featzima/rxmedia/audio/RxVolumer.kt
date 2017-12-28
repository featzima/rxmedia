package com.featzima.rxmedia.audio

import org.reactivestreams.Subscription
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RxVolumer(
        private val muteStartUs: Long,
        private val muteEndUs: Long,
        private val changingWindowUs: Long,
        private val muteLevel: Double) : RxAudioBufferProcessor() {

    init {
        if (changingWindowUs == 0L) throw IllegalArgumentException("changingWindowUs can't be 0")
    }

    private var encodedBytes: Long = 0

    override fun processBuffer(subscription: Subscription, buffer: ByteBuffer): Boolean {
        val currentUs = PcmCalculator.presentationTimeByEncodedBytes(encodedBytes)
        if (currentUs in (muteStartUs - changingWindowUs)..(muteEndUs + changingWindowUs)) {
            val shortBuffer = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (i in 0 until shortBuffer.limit()) {
                val momentUs = PcmCalculator.presentationTimeByEncodedBytes(encodedBytes + i * Short.BYTE_SIZES)
                val multiplier: Double = when {
                    momentUs < muteStartUs -> 1 - (1 - muteLevel) * (momentUs - (muteStartUs - changingWindowUs)) / changingWindowUs.toDouble()
                    momentUs > muteEndUs -> 1 - muteLevel * (momentUs - (muteStartUs - changingWindowUs)) / changingWindowUs.toDouble()
                    else -> muteLevel
                }
                shortBuffer.put(i, (shortBuffer[i] * multiplier).toShort())
            }
        }
        encodedBytes += buffer.remaining()
        emitter.onNext(buffer)
        return true
    }

    companion object {
        private val Short.Companion.BYTE_SIZES
            get() = 2
    }
}