package com.featzima.rxmedia.video

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaFormat
import android.renderscript.RenderScript
import android.util.Log
import com.featzima.rxmedia.extensions.transferToAsMuchAsPossible
import com.featzima.rxmedia.extensions.waitForRequested
import com.featzima.rxmedia.i.CodecEvent
import com.featzima.rxmedia.i.DataCodecEvent
import com.featzima.rxmedia.i.FormatCodecEvent
import com.featzima.rxmedia.video.internal.CodecOutputSurface
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.nio.ByteBuffer


class RxVideoDecoder(context: Context) {

    private lateinit var outputSurface: CodecOutputSurface
    private val TIMEOUT_USEC = 10000L
    private var decodeCount = 0
    private var rotationDegrees = 0
    private val codecSubject = BehaviorSubject.create<MediaCodec>()
    private val formatSubject = BehaviorSubject.create<MediaFormat>()
    private val renderScript = RenderScript.create(context)

    val input: Subscriber<CodecEvent<ByteBuffer>> = object : Subscriber<CodecEvent<ByteBuffer>> {
        lateinit var subscription: Subscription

        override fun onSubscribe(s: Subscription) {
            this.subscription = s
            this.subscription.request(1)
        }

        override fun onError(t: Throwable) {
            Log.e(TAG, "onError($t)")
        }

        override fun onNext(codecEvent: CodecEvent<ByteBuffer>) {
            when (codecEvent) {
                is FormatCodecEvent -> {
                    onNextFormatCodecEvent(codecEvent.mediaFormat)
                }
                is DataCodecEvent -> {
                    onNextDataCodecEvent(codecEvent.data, codecEvent.bufferInfo)
                }
            }
            subscription.request(1)
        }

        override fun onComplete() {
            val mediaCodec = codecSubject.blockingFirst()
            loop@ while (true) {
                val inputBufferIndex = mediaCodec.dequeueInputBuffer(TIMEOUT_USEC)
                when (inputBufferIndex) {
                    in 0..Int.MAX_VALUE -> {
                        mediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        break@loop
                    }
                }
            }
        }

    }

    private fun onNextFormatCodecEvent(format: MediaFormat) {
        Log.d(TAG, "Video size is " + format.getInteger(MediaFormat.KEY_WIDTH) + "x" +
                format.getInteger(MediaFormat.KEY_HEIGHT))

        if (format.containsKey("rotation-degrees")) {
            rotationDegrees = format.getInteger("rotation-degrees")
            format.setInteger("rotation-degrees", 0)
        }

        formatSubject.onNext(format)
    }

    private fun onNextDataCodecEvent(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        val mediaCodec = codecSubject.blockingFirst()
        loop@ while (buffer.hasRemaining()) {
            val inputBufferIndex = mediaCodec.dequeueInputBuffer(TIMEOUT_USEC)
            when (inputBufferIndex) {
                in 0..Int.MAX_VALUE -> {
                    val inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex)
                    inputBuffer.clear()
                    val bytesInputted = buffer.transferToAsMuchAsPossible(inputBuffer)
                    mediaCodec.queueInputBuffer(inputBufferIndex, 0, bytesInputted, bufferInfo.presentationTimeUs, 0)
                }
            }
        }
    }

    fun output() = Flowable.create<CodecEvent<Bitmap>>({ emitter ->
        val format = formatSubject
                .subscribeOn(Schedulers.io())
                .blockingFirst()
        outputSurface = CodecOutputSurface(format.getInteger("width"), format.getInteger("height"))
        val mime = format.getString(MediaFormat.KEY_MIME)
        val mediaCodec = MediaCodec.createDecoderByType(mime)
        mediaCodec.configure(format, null, null, 0)
        mediaCodec.start()
        this.codecSubject.onNext(mediaCodec)

        Log.d(TAG, "loop")
        loop@ while (emitter.waitForRequested()) {

            val info = MediaCodec.BufferInfo()
            val decoderStatus = mediaCodec.dequeueOutputBuffer(info, TIMEOUT_USEC)

            @SuppressLint("SwitchIntDef")
            when (decoderStatus) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = mediaCodec.outputFormat
                    Log.v(TAG, "INFO_OUTPUT_FORMAT_CHANGED: $newFormat")
                    emitter.onNext(FormatCodecEvent(newFormat))
                }
                in 0..Int.MAX_VALUE -> {
                    Log.d(TAG, "surface mediaCodec given buffer $decoderStatus (size=${info.size})")
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.d(TAG, "output EOS")
                        emitter.onComplete()
                        break@loop
                    }

                    try {
                        val image: Image = mediaCodec.getOutputImage(decoderStatus)
                        val bitmap: Bitmap = RenderScriptUtils.YUV_420_888_toRGB(renderScript, image, image.width, image.height)
                        mediaCodec.releaseOutputBuffer(decoderStatus, true)
                        emitter.onNext(DataCodecEvent(bitmap, info))
                        image.close()
                    } catch (e: Throwable) {
                        Log.e(TAG, "!!!", e)
                    }
                }
            }
        }
        release()
    }, BackpressureStrategy.BUFFER)


    private fun release() {
        val mediaCodec = codecSubject.blockingFirst()
        this.outputSurface.release()
        mediaCodec.release()
    }

    companion object {
        private val TAG = RxVideoDecoder::class.java.simpleName
    }

}
