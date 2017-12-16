package com.featzima.rxmedia.video

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.featzima.rxmedia.i.CodecEvent
import com.featzima.rxmedia.i.DataCodecEvent
import com.featzima.rxmedia.i.FormatCodecEvent
import com.featzima.rxmedia.i.IRxVideoCodec
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

class RxVideoCodec(
        private val settings: VideoCodecSettings) : IRxVideoCodec {

    private val codec: MediaCodec
    private val surface: Surface

    private var nbEncoded: Int = 0
    private var isInputCompleted = false

    init {
        val mediaFormat = MediaFormat.createVideoFormat(settings.videoMime, settings.width, settings.height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, calcBitRate(settings))
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.frameRate)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, settings.keyFrameInterval)
        }

        this.codec = MediaCodec.createEncoderByType(settings.videoMime)
        this.codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        this.surface = codec.createInputSurface()
        this.codec.start()
    }

    override fun output(): Publisher<CodecEvent> = Flowable.create<CodecEvent>({ emitter ->
        Log.d(TAG, "emitter ${emitter}")
        while (!emitter.isCancelled && !isInputCompleted) {
            val info = MediaCodec.BufferInfo()
            val outputBufferIndex = codec.dequeueOutputBuffer(info, TIMEOUT_USEC)
//            Log.d(TAG, "outputBufferIndex = $outputBufferIndex")

            @SuppressLint("SwitchIntDef")
            when (outputBufferIndex) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
//                    Log.d(TAG, "INFO_TRY_AGAIN_LATER")
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "INFO_OUTPUT_FORMAT_CHANGED")
                    emitter.onNext(FormatCodecEvent(mediaFormat = codec.outputFormat))
                }
                in 0..Int.MAX_VALUE -> {
                    val encodedData = this.codec.getOutputBuffer(outputBufferIndex)

                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // The codec config data was pulled out and fed to the muxer when we got
                        // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                        Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG")
                        info.size = 0
                    }

                    if (info.size != 0) {
                        info.presentationTimeUs = computePresentationTime(nbEncoded, settings.frameRate)
                        Log.e(TAG, "presentationTime = ${info.presentationTimeUs}")
                        emitter.onNext(DataCodecEvent(
                                byteBuffer = encodedData,
                                bufferInfo = info))
                        this.nbEncoded++
                    }

                    codec.releaseOutputBuffer(outputBufferIndex, false)
                }
            } }
        Log.d(TAG, "emitter.onComplete()")
        codec.stop()
        codec.release()
        emitter.onComplete()
    }, BackpressureStrategy.BUFFER)

    private fun computePresentationTime(frameIndex: Int, frameRate: Int): Long {
        return (frameIndex * 1000000 / frameRate).toLong()
    }

    override fun input(): Subscriber<Bitmap> = object : Subscriber<Bitmap> {
        override fun onComplete() {
            Log.d(TAG, "onComplete()")
            isInputCompleted = true
        }

        override fun onSubscribe(s: Subscription) {
            Log.d(TAG, "onSubscribe($s)")
        }

        override fun onNext(frame: Bitmap) {
//            Log.d(TAG, "onNext()")
            val canvas = surface.lockCanvas(Rect(0, 0, settings.width, settings.height))
            canvas.drawBitmap(frame, 0f, 0f, Paint())
            surface.unlockCanvasAndPost(canvas)
            frame.recycle()
        }

        override fun onError(t: Throwable) {
            Log.d(TAG, "onError($t)")
        }
    }

    companion object {
        private val TAG = RxVideoCodec::class.java.simpleName
        private val TIMEOUT_USEC = 10000L
        private val BPP = 1f

        private fun calcBitRate(settings: VideoCodecSettings): Int {
            val bitrate = (BPP * settings.frameRate.toFloat() * settings.width.toFloat() * settings.height.toFloat()).toInt()
            Log.v(TAG, String.format("bitrate=%5.2f[Mbps]", bitrate.toFloat() / 1024f / 1024f))
            return bitrate
        }
    }

}