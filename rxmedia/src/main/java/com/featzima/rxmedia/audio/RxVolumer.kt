package com.featzima.rxmedia.audio

import android.util.Log
import org.reactivestreams.Subscription
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.experimental.*

class RxVolumer(
        private val muteStartUs: Long,
        private val muteEndUs: Long,
        private val changingWindowUs: Long,
        private val muteLevel: Double) : RxAudioBufferProcessor() {

    private var encodedBytes: Long = 0

    override fun processBuffer(subscription: Subscription, buffer: ByteBuffer): Boolean {
        val currentUs = PcmCalculator.presentationTimeByEncodedBytes(encodedBytes)
        if (currentUs in (muteStartUs - changingWindowUs)..(muteEndUs + changingWindowUs)) {
            val shortBuffer = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (i in 0 until shortBuffer.limit()) {
                val momentUs = PcmCalculator.presentationTimeByEncodedBytes(encodedBytes + i * Short.BYTE_SIZES)
                val multiplier: Double = when {
                    momentUs < muteStartUs -> 1 - (1 - muteLevel) * (momentUs - (muteStartUs - changingWindowUs)) / changingWindowUs.toDouble()
                    momentUs > muteEndUs -> muteLevel
                    else -> muteLevel
                }
//                printValue(multiplier.format(2))
                shortBuffer.put(i, (shortBuffer[i] * multiplier).toShort())
            }
        }
        encodedBytes += buffer.remaining()
        emitter.onNext(buffer)
        return true
    }

//    fun Double.format(digits: Int) = java.lang.String.format("%.${digits}f", this)
//
//    private var prevValue = ""
//    fun printValue(value: String) {
//        if (prevValue != value) {
//            prevValue = value
//            Log.e("!!!", "muteLevel = $value")
//        }
//    }

    companion object {
        private val Short.Companion.BYTE_SIZES
            get() = 2
    }
}