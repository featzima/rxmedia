package com.featzima.rxmedia.video

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaFormat.*
import android.opengl.GLES20
import android.util.Log
import com.featzima.rxmedia.extensions.waitForRequested
import com.featzima.rxmedia.i.CodecEvent
import com.featzima.rxmedia.i.DataCodecEvent
import com.featzima.rxmedia.i.FormatCodecEvent
import com.featzima.rxmedia.i.IRxVideoCodec
import com.featzima.rxmedia.video.internal.CodecInputSurface
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

    private var frameIndex = 0

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
                        surface = CodecInputSurface(codec.createInputSurface())
                        surfaceRect = Rect(0, 0, codecEvent.mediaFormat.getInteger(KEY_WIDTH), codecEvent.mediaFormat.getInteger(KEY_HEIGHT))
                        frameRate = codecEvent.mediaFormat.getInteger(KEY_FRAME_RATE)
                        codec.start()
                        codecSubject.onNext(codec)
                    }
                    is DataCodecEvent -> {
                        val frameBitmap: Bitmap = codecEvent.data
                        frameBitmap.recycle()

                        Log.w(TAG, "makeCurrent() from Thread #${Thread.currentThread().id}")
                        surface!!.makeCurrent()
                        generateSurfaceFrame(frameIndex++)
                        Log.e("!!!", "${codecEvent.bufferInfo.presentationTimeUs}")
                        surface!!.setPresentationTime(codecEvent.bufferInfo.presentationTimeUs * 1000)
                        surface!!.swapBuffers()
                        Log.w(TAG, "swapBuffers() from Thread #${Thread.currentThread().id}")

//                    var canvas: Canvas? = null
//                    try {
//                        canvas = surface!!.lockCanvas(surfaceRect)
//                        frameBitmap = codecEvent.data
//                        canvas.drawBitmap(frameBitmap, 0f, 0f, Paint())
//                    } finally {
//                        canvas.let { surface!!.unlockCanvasAndPost(it) }
//                        frameBitmap?.recycle()
//                    }
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

    // RGB color values for generated frames
    private val TEST_R0 = 0
    private val TEST_G0 = 136
    private val TEST_B0 = 0
    private val TEST_R1 = 236
    private val TEST_G1 = 50
    private val TEST_B1 = 186

    /**
     * Generates a frame of data using GL commands.  We have an 8-frame animation
     * sequence that wraps around.  It looks like this:
     * <pre>
     * 0 1 2 3
     * 7 6 5 4
    </pre> *
     * We draw one of the eight rectangles and leave the rest set to the clear color.
     */
    private fun generateSurfaceFrame(frameIndex: Int) {
        var frameIndex = frameIndex
        frameIndex %= 8

        val startX: Int
        val startY: Int
        if (frameIndex < 4) {
            // (0,0) is bottom-left in GL
            startX = frameIndex * (surfaceRect.width() / 4)
            startY = surfaceRect.height() / 2
        } else {
            startX = (7 - frameIndex) * (surfaceRect.width() / 4)
            startY = 0
        }

        GLES20.glClearColor(TEST_R0 / 255.0f, TEST_G0 / 255.0f, TEST_B0 / 255.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(startX, startY, surfaceRect.width() / 4, surfaceRect.height() / 2)
        GLES20.glClearColor(TEST_R1 / 255.0f, TEST_G1 / 255.0f, TEST_B1 / 255.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    override fun output(): Publisher<CodecEvent<ByteBuffer>> = Flowable.create<CodecEvent<ByteBuffer>>({ emitter ->
        loop@ while (!emitter.isCancelled) {
            val codec = this.codecSubject.blockingFirst()
//            emitter.waitForRequested()

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