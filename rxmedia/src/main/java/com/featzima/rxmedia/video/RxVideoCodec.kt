package com.featzima.rxmedia.video

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaFormat.*
import android.util.Log
import com.featzima.rxmedia.extensions.waitForRequested
import com.featzima.rxmedia.i.CodecEvent
import com.featzima.rxmedia.i.DataCodecEvent
import com.featzima.rxmedia.i.FormatCodecEvent
import com.featzima.rxmedia.i.IRxVideoCodec
import com.featzima.rxmedia.video.internal.CodecInputSurface
import com.featzima.rxmedia.video.internal.InputTextureRender
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.BehaviorSubject
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.nio.ByteBuffer


class RxVideoCodec(
        private val settings: VideoCodecSettings) : IRxVideoCodec, Disposable {

    private var surface: CodecInputSurface? = null
    private lateinit var surfaceRect: Rect
    private var frameRate: Int = 0

    private var nbEncoded: Int = 0
    private var canceled = false
    private val codecSubject = BehaviorSubject.create<MediaCodec>()
    private val inputTextureRender = InputTextureRender()

    override fun dispose() {
        canceled = true
    }

    override fun isDisposed(): Boolean {
        return canceled
    }

    override fun input(): Subscriber<CodecEvent<Bitmap>> = object : Subscriber<CodecEvent<Bitmap>> {

        private lateinit var subscription: Subscription

        override fun onComplete() {
            surface!!.release()
            codecSubject.value?.signalEndOfInputStream()
        }

        override fun onSubscribe(s: Subscription) {
            subscription = s
            subscription.request(1)
        }

        override fun onNext(codecEvent: CodecEvent<Bitmap>) {
            try {
                when (codecEvent) {
                    is FormatCodecEvent -> {
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
                        surface = CodecInputSurface(codec.createInputSurface())
                        surfaceRect = Rect(0, 0, codecEvent.mediaFormat.getInteger(KEY_WIDTH), codecEvent.mediaFormat.getInteger(KEY_HEIGHT))
                        frameRate = codecEvent.mediaFormat.getInteger(KEY_FRAME_RATE)
                        codec.start()
                        codecSubject.onNext(codec)
                    }
                    is DataCodecEvent -> {
                        surface!!.apply {
                            makeCurrent()
                            inputTextureRender.drawBitmap(codecEvent.data)
                            setPresentationTime(codecEvent.bufferInfo.presentationTimeUs * 1000)
                            swapBuffers()
                            codecEvent.data.recycle()
                        }
                    }
                }
                subscription.request(1)
            } catch (e: Exception) {
                subscription.cancel()
                codecSubject.onError(e)
            }
        }

        override fun onError(t: Throwable) {
            Log.e(TAG, "onError($t)")
            codecSubject.onError(t)
        }
    }

    override fun output(): Publisher<CodecEvent<ByteBuffer>> = Flowable.create<CodecEvent<ByteBuffer>>({ emitter ->
        loop@ while (!emitter.isCancelled) {
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

                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break@loop
                    }

                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // The codec config data was pulled out and fed to the muxer when we got
                        // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                        Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG")
                        info.size = 0
                    }

                    if (info.size != 0) {
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