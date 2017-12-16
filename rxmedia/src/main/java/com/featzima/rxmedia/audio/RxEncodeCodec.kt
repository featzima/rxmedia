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

class RxEncodeCodec(
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

    private var reallyReceived = 0L

    val input: Subscriber<ByteBuffer> = object : Subscriber<ByteBuffer> {
            var totalBytesInputted = 0L
            lateinit var subscription: Subscription

            override fun onSubscribe(s: Subscription) {
                Log.i(TAG, "onSubscribe")
                this.subscription = s
                this.subscription.request(1)
            }

            override fun onNext(buffer: ByteBuffer) {
                reallyReceived += buffer.remaining()
                Log.i(TAG, "onNext")
                val codec = this@RxEncodeCodec.codecSubject.blockingFirst()
                loop@ while (buffer.hasRemaining()) {
                    val inputBufferIndex = codec.dequeueInputBuffer(this@RxEncodeCodec.timeoutUsec)
                    when (inputBufferIndex) {
                        in 0..Int.MAX_VALUE -> {
                            val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                            inputBuffer.clear()
                            val bytesInputted = buffer.transferToAsMuchAsPossible(inputBuffer)
                            val presentationTimeUs = presentationTimeCalculator(totalBytesInputted)
                            codec.queueInputBuffer(inputBufferIndex, 0, bytesInputted, presentationTimeUs, 0)
                            totalBytesInputted += bytesInputted
                            Log.i(TAG, "input::presentationTimeUs = $presentationTimeUs")
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
                Log.i("!!!", "onComplete ($totalBytesInputted, really = $reallyReceived)")
                val codec = this@RxEncodeCodec.codecSubject.blockingFirst()
                loop@ while (true) {
                    val inputBufferIndex = codec.dequeueInputBuffer(this@RxEncodeCodec.timeoutUsec)
                    when (inputBufferIndex) {
                        in 0..Int.MAX_VALUE -> {
                            val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                            inputBuffer.limit(0)
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            break@loop
                        }
                    }
                }
            }
        }

    val output = Flowable.create<CodecEvent<ByteBuffer>>({ emitter ->
            val codec = this@RxEncodeCodec.codecSubject.blockingFirst()
            while (!emitter.isCancelled) {
//                Log.e("RxPcmProcessor $TAG", "${emitter.requested()}")
                emitter.waitForRequested()

                val info = MediaCodec.BufferInfo()
                val outputBufferIndex = codec.dequeueOutputBuffer(info, this@RxEncodeCodec.timeoutUsec)

                if (info.flags.isFlagSet(MediaCodec.BUFFER_FLAG_END_OF_STREAM)) {
                    break
                }

                @SuppressLint("SwitchIntDef")
                when (outputBufferIndex) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        emitter.onNext(FormatCodecEvent(codec.outputFormat))
                    }
                    in 0..Int.MAX_VALUE -> {
                        Log.i(TAG, "output::presentationTimeUs = ${info.presentationTimeUs}")
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        emitter.onNext(DataCodecEvent(
                                data = outputBuffer.exactlyCopy(),
                                bufferInfo = info))
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }
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