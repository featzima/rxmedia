package com.featzima.rxmedia.audio

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.util.Log
import com.featzima.rxmedia.extensions.exactlyCopy
import com.featzima.rxmedia.extensions.isFlagSet
import com.featzima.rxmedia.extensions.transferToAsMuchAsPossible
import com.featzima.rxmedia.extensions.waitForRequested
import com.featzima.rxmedia.i.CodecEvent
import com.featzima.rxmedia.i.DataCodecEvent
import com.featzima.rxmedia.i.FormatCodecEvent
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.subjects.BehaviorSubject
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.nio.ByteBuffer

class RxCodec(
        codec: MediaCodec? = null,
        private val presentationTimeCalculator: (encodedBytes: Long) -> Long,
        private val TAG: String = RxCodec::class.java.simpleName,
        private val timeoutUsec: Long = 1000L) {

    private val codecSubject: BehaviorSubject<MediaCodec> = BehaviorSubject.create<MediaCodec>()

    init {
        codec?.let { codecSubject.onNext(it) }
    }

    fun setCodec(codec: MediaCodec) = this.codecSubject.onNext(codec)

    val input: Subscriber<ByteBuffer> = object : Subscriber<ByteBuffer> {
        var totalBytesInputted = 0L
        lateinit var subscription: Subscription

        override fun onSubscribe(s: Subscription) {
            this.subscription = s
            this.subscription.request(1)
        }

        override fun onNext(buffer: ByteBuffer) {
            val codec = this@RxCodec.codecSubject.blockingFirst()
            loop@ while (buffer.hasRemaining()) {
                val inputBufferIndex = codec.dequeueInputBuffer(this@RxCodec.timeoutUsec)
                when (inputBufferIndex) {
                    in 0..Int.MAX_VALUE -> {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        inputBuffer.clear()
                        val bytesInputted = buffer.transferToAsMuchAsPossible(inputBuffer)
                        val presentationTimeUs = presentationTimeCalculator(totalBytesInputted)
                        codec.queueInputBuffer(inputBufferIndex, 0, bytesInputted, presentationTimeUs, 0)
                        totalBytesInputted += bytesInputted
                    }
                }
            }
            this.subscription.request(1)
        }

        override fun onError(t: Throwable) {
            Log.e(TAG, "onError", t)
            this@RxCodec.codecSubject.onError(t)
        }

        override fun onComplete() {
            Log.d(TAG, "onComplete")
            val codec = this@RxCodec.codecSubject.blockingFirst()
            loop@ while (true) {
                val inputBufferIndex = codec.dequeueInputBuffer(this@RxCodec.timeoutUsec)
                when (inputBufferIndex) {
                    in 0..Int.MAX_VALUE -> {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        inputBuffer.clear()
                        codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        break@loop
                    }
                }
            }
        }
    }

    val output = Flowable.create<CodecEvent<ByteBuffer>>({ emitter ->
        loop@ while (!emitter.isCancelled) {
            val codec = this.codecSubject.blockingFirst()
            emitter.waitForRequested()

            val info = MediaCodec.BufferInfo()
            val outputBufferIndex = codec.dequeueOutputBuffer(info, this.timeoutUsec)

            @SuppressLint("SwitchIntDef")
            when (outputBufferIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    emitter.onNext(FormatCodecEvent(codec.outputFormat))
                }
                in 0..Int.MAX_VALUE -> {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    emitter.onNext(DataCodecEvent(
                            data = outputBuffer.exactlyCopy(),
                            bufferInfo = info))
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                }
            }

            if (info.flags.isFlagSet(MediaCodec.BUFFER_FLAG_END_OF_STREAM)) {
                break@loop
            }
        }
        this.codecSubject.value?.apply {
            stop()
            release()
        }
        emitter.onComplete()
    }, BackpressureStrategy.BUFFER)

}