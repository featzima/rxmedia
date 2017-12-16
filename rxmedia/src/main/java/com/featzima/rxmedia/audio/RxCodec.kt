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
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.nio.ByteBuffer

class RxCodec(
        private val TAG: String,
        private val codec: MediaCodec? = null,
        private val presentationTimeCalculator: (encodedBytes: Long) -> Long,
        private val timeoutUsec: Long = 1000L) {

    private val codecSubject: BehaviorSubject<MediaCodec> =
            if (codec != null)
                BehaviorSubject.createDefault<MediaCodec>(codec)
            else
                BehaviorSubject.create<MediaCodec>()

    fun setCodec(codec: MediaCodec) = this.codecSubject.onNext(codec)

    val input: Subscriber<ByteBuffer> = object : Subscriber<ByteBuffer> {
            var totalBytesInputted = 0L
            lateinit var subscription: Subscription

            override fun onSubscribe(s: Subscription) {
                Log.e(TAG, "onSubscribe")
                this.subscription = s
                this.subscription.request(1)
            }

            override fun onNext(buffer: ByteBuffer) {
//                Log.e(TAG, "onNext(${buffer.remaining()})")
                val codec = this@RxCodec.codecSubject.blockingFirst()
                loop@ while (buffer.hasRemaining()) {
                    var inputBufferIndex = -1
                    try {
                        inputBufferIndex = codec.dequeueInputBuffer(this@RxCodec.timeoutUsec)
                    } catch (e: Exception) {
                        Log.e(TAG, "problem")
                    }
                    when (inputBufferIndex) {
                        in 0..Int.MAX_VALUE -> {
                            val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                            inputBuffer.clear()
                            val bytesInputted = buffer.transferToAsMuchAsPossible(inputBuffer)
                            val presentationTimeUs = presentationTimeCalculator(totalBytesInputted)
                            codec.queueInputBuffer(inputBufferIndex, 0, bytesInputted, presentationTimeUs, 0)
                            totalBytesInputted += bytesInputted
//                            Log.e(TAG, "input::presentationTimeUs = $presentationTimeUs")
                        }
                    }
                }
                this.subscription.request(1)
            }

            override fun onError(t: Throwable) {
                Log.e(TAG, "onError")
                Log.e(TAG, t.message, t)
            }

            override fun onComplete() {
                Log.e(TAG, "onComplete")
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
            val codec = this@RxCodec.codecSubject.blockingFirst()
            while (!emitter.isCancelled) {
//                Log.e(TAG, "requested = ${emitter.requested()}")

                val info = MediaCodec.BufferInfo()
                val outputBufferIndex = codec.dequeueOutputBuffer(info, this@RxCodec.timeoutUsec)

                @SuppressLint("SwitchIntDef")
                when (outputBufferIndex) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        emitter.onNext(FormatCodecEvent(codec.outputFormat))
                    }
                    in 0..Int.MAX_VALUE -> {
//                        Log.e(TAG, "output::presentationTimeUs = ${info.presentationTimeUs}")
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        emitter.onNext(DataCodecEvent(
                                data = outputBuffer.exactlyCopy(),
                                bufferInfo = info))
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }

                if (info.flags.isFlagSet(MediaCodec.BUFFER_FLAG_END_OF_STREAM)) {
                    break
                }
            }
            codec.stop()
            codec.release()
            emitter.onComplete()
        }, BackpressureStrategy.BUFFER)

    companion object {
//        val TAG = RxCodec::class.java.simpleName
    }
}