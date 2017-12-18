package com.featzima.rxmedia.video

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaFormat.*
import android.util.Log
import android.view.Surface
import com.featzima.rxmedia.extensions.waitForRequested
import com.featzima.rxmedia.i.CodecEvent
import com.featzima.rxmedia.i.DataCodecEvent
import com.featzima.rxmedia.i.FormatCodecEvent
import com.featzima.rxmedia.i.IRxVideoCodec
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.subjects.BehaviorSubject
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.nio.ByteBuffer

class RxVideoCodec(
        private val settings: VideoCodecSettings) : IRxVideoCodec {

    private var surface: Surface? = null
    private lateinit var surfaceRect: Rect
    private var frameRate: Int = 0

    private var nbEncoded: Int = 0
    private var isInputCompleted = false
    private val codecSubject = BehaviorSubject.create<MediaCodec>()

    override fun input(): Subscriber<CodecEvent<Bitmap>> = object : Subscriber<CodecEvent<Bitmap>> {
        override fun onComplete() {
            isInputCompleted = true
        }

        override fun onSubscribe(s: Subscription) {
        }

        override fun onNext(codecEvent: CodecEvent<Bitmap>) {
            when (codecEvent) {
                is FormatCodecEvent -> {
                    if (surface != null) return
                    if (!codecEvent.mediaFormat.containsKey(KEY_WIDTH)
                            || !codecEvent.mediaFormat.containsKey(KEY_HEIGHT)
                            || !codecEvent.mediaFormat.containsKey(KEY_FRAME_RATE)) {
                        throw IllegalArgumentException("MediaFormat should contain KEY_WIDTH, KEY_HEIGHT, KEY_FRAME_RATE")
                    }
                    val mediaFormat = codecEvent.mediaFormat.apply {
                        setString(KEY_MIME, settings.videoMime)
                        setInteger(MediaFormat.KEY_BIT_RATE, calcBitRate(this))
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, settings.keyFrameInterval)
                    }
                    val codec = MediaCodec.createEncoderByType(settings.videoMime)
                    codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    surface = codec.createInputSurface()
                    surfaceRect = Rect(0, 0, codecEvent.mediaFormat.getInteger(KEY_WIDTH), codecEvent.mediaFormat.getInteger(KEY_HEIGHT))
                    frameRate = codecEvent.mediaFormat.getInteger(KEY_FRAME_RATE)
                    codec.start()
                    codecSubject.onNext(codec)
                }
                is DataCodecEvent -> {
                    var canvas: Canvas? = null
                    var frameBitmap: Bitmap? = null
                    try {
                        canvas = surface!!.lockCanvas(surfaceRect)
                        frameBitmap = codecEvent.data
                        canvas.drawBitmap(frameBitmap, 0f, 0f, Paint())
                    } catch (e: Exception) {
                        Log.e(TAG, e.message, e)
                    } finally {
                        canvas.let { surface!!.unlockCanvasAndPost(it) }
                        frameBitmap?.recycle()
                    }

                }
            }
        }

        override fun onError(t: Throwable) {
            Log.e(TAG, "onError($t)")
            codecSubject.onError(t)
        }
    }

    override fun output(): Publisher<CodecEvent<ByteBuffer>> = Flowable.create<CodecEvent<ByteBuffer>>({ emitter ->
        while (!emitter.isCancelled && !isInputCompleted) {
            val codec = this.codecSubject.blockingFirst()
            emitter.waitForRequested()

            val info = MediaCodec.BufferInfo()
            val outputBufferIndex = codec.dequeueOutputBuffer(info, TIMEOUT_USEC)

            @SuppressLint("SwitchIntDef")
            when (outputBufferIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "INFO_OUTPUT_FORMAT_CHANGED")
                    emitter.onNext(FormatCodecEvent(mediaFormat = codec.outputFormat))
                }
                in 0..Int.MAX_VALUE -> {
                    val encodedData = codec.getOutputBuffer(outputBufferIndex)

                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // The codec config data was pulled out and fed to the muxer when we got
                        // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                        Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG")
                        info.size = 0
                    }

                    if (info.size != 0) {
                        info.presentationTimeUs = computePresentationTime(nbEncoded, frameRate)
                        emitter.onNext(DataCodecEvent(
                                data = encodedData,
                                bufferInfo = info))
                        this.nbEncoded++
                    }

                    codec.releaseOutputBuffer(outputBufferIndex, false)
                }
            }
        }
        this.codecSubject.value?.apply {
            stop()
            release()
        }
        emitter.onComplete()
    }, BackpressureStrategy.BUFFER)

    private fun computePresentationTime(frameIndex: Int, frameRate: Int): Long {
        return (frameIndex * 1000000 / frameRate).toLong()
    }

    companion object {
        private val TAG = RxVideoCodec::class.java.simpleName
        private val TIMEOUT_USEC = 10000L

        private fun calcBitRate(mediaFormat: MediaFormat): Int {
            val frameRate = mediaFormat.getInteger(KEY_FRAME_RATE)
            val width = mediaFormat.getInteger(KEY_WIDTH)
            val height = mediaFormat.getInteger(KEY_HEIGHT)
            val bitrate = frameRate * width * height
            Log.v(TAG, String.format("bitrate=%5.2f[Mbps]", bitrate.toFloat() / 1024f / 1024f))
            return bitrate
        }
    }

}