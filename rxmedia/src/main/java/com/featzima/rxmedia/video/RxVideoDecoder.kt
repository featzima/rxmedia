package com.featzima.rxmedia.video

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.featzima.rxmedia.extensions.transferToAsMuchAsPossible
import com.featzima.rxmedia.i.CodecEvent
import com.featzima.rxmedia.i.DataCodecEvent
import com.featzima.rxmedia.i.FormatCodecEvent
import com.featzima.rxmedia.video.internal.CodecOutputSurface
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import junit.framework.Assert.fail
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.nio.ByteBuffer

class RxVideoDecoder {

    private lateinit var outputSurface: CodecOutputSurface
    private val TIMEOUT_USEC = 10000L
    private var decodeCount = 0
    private var rotationDegrees = 0
    private val codecSubject = BehaviorSubject.create<MediaCodec>()
    private val formatSubject = BehaviorSubject.create<MediaFormat>()

    val input: Subscriber<CodecEvent> = object : Subscriber<CodecEvent> {
        lateinit var subscription: Subscription

        override fun onSubscribe(s: Subscription) {
            this.subscription = s
            this.subscription.request(1)
        }

        override fun onError(t: Throwable) {
        }

        override fun onNext(codecEvent: CodecEvent) {
            when (codecEvent) {
                is FormatCodecEvent -> {
                    configureCoded(codecEvent.mediaFormat)
                }
                is DataCodecEvent -> {
                    decodeRawBuffer(codecEvent.byteBuffer, codecEvent.bufferInfo)
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

    private fun configureCoded(format: MediaFormat) {
        Log.d(TAG, "Video size is " + format.getInteger(MediaFormat.KEY_WIDTH) + "x" +
                format.getInteger(MediaFormat.KEY_HEIGHT))

        if (format.containsKey("rotation-degrees")) {
            rotationDegrees = format.getInteger("rotation-degrees")
            format.setInteger("rotation-degrees", 0)
        }

        formatSubject.onNext(format)
    }

    private fun decodeRawBuffer(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
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

    fun output() = Flowable.create<Bitmap>({ emitter ->
        val format = formatSubject
                .subscribeOn(Schedulers.io())
                .blockingFirst()
        outputSurface = CodecOutputSurface(format.getInteger("width"), format.getInteger("height"))
        val mime = format.getString(MediaFormat.KEY_MIME)
        val mediaCodec = MediaCodec.createDecoderByType(mime)
        mediaCodec.configure(format, this.outputSurface.surface, null, 0)
        mediaCodec.start()
        this.codecSubject.onNext(mediaCodec)

        Log.d(TAG, "loop")
        loop@ while (!emitter.isCancelled) {
//            emitter.waitForRequested()

            val info = MediaCodec.BufferInfo()
            val decoderStatus = mediaCodec.dequeueOutputBuffer(info, TIMEOUT_USEC.toLong())
            if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                Log.d(TAG, "no output from mediaCodec available")
            } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not important for s, since we're using Surface
                Log.d(TAG, "mediaCodec output buffers changed")
            } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = mediaCodec.outputFormat
                Log.d(TAG, "mediaCodec output format changed: " + newFormat)
            } else if (decoderStatus < 0) {
                fail("unexpected result from mediaCodec.dequeueOutputBuffer: " + decoderStatus)
            } else { // decoderStatus >= 0
                Log.d(TAG, "surface mediaCodec given buffer " + decoderStatus +
                        " (size=" + info.size + ")")
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    Log.d(TAG, "output EOS")
                    emitter.onComplete()
                    break@loop
                }

                val doRender = info.size != 0

                // As soon as we call releaseOutputBuffer, the buffer will be forwarded
                // to SurfaceTexture to convert to a texture.  The API doesn't guarantee
                // that the texture will be available before the call returns, so we
                // need to wait for the onFrameAvailable callback to fire.
                mediaCodec.releaseOutputBuffer(decoderStatus, doRender)
                if (doRender) {
                    this.decodeCount++
                    val expectedPresentationTimeUs =
                            Log.d(TAG, "awaiting decode of frame $decodeCount, time ${info.presentationTimeUs}")
                    outputSurface.awaitNewImage()
                    Log.d(TAG, "awaited")
                    outputSurface.drawImage(true)

                    if (decodeCount < MAX_FRAMES) {
                        val frame = outputSurface.frameBitmap
                        var emittedFrame = frame
                        if (rotationDegrees != 0) {
                            val matrix = Matrix()
                            matrix.setRotate(rotationDegrees.toFloat())
                            emittedFrame = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, false)
                        }
                        emitter.onNext(emittedFrame)
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
        private val MAX_FRAMES = 100000       // stop extracting after this many
    }


}
