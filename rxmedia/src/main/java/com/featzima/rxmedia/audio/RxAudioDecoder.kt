package com.featzima.rxmedia.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.featzima.rxmedia.i.CodecEvent
import com.featzima.rxmedia.i.DataCodecEvent
import com.featzima.rxmedia.i.FormatCodecEvent
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription


class RxAudioDecoder {
    private val rxCodec = RxCodec(
            TAG = "Audio.Decoder",
            presentationTimeCalculator = { encodedBytes -> 1000000L * (encodedBytes / 2) / 44100 })

    val input: Subscriber<CodecEvent>
        get() = object : Subscriber<CodecEvent> {
            lateinit var s: Subscription
            override fun onNext(event: CodecEvent) {
                when (event) {
                    is FormatCodecEvent -> {
                        val mime = event.mediaFormat.getString(MediaFormat.KEY_MIME)
                        val codec = MediaCodec.createDecoderByType(mime)
                        codec.configure(event.mediaFormat, null, null, 0)
                        codec.start()
                        rxCodec.setCodec(codec)
                        rxCodec.input.onSubscribe(s)
                    }
                    is DataCodecEvent -> {
                        rxCodec.input.onNext(event.byteBuffer)
                    }
                }
            }

            override fun onError(t: Throwable) = rxCodec.input.onError(t)

            override fun onComplete() = rxCodec.input.onComplete()

            override fun onSubscribe(s: Subscription) {
                this.s = s
                this.s.request(1)
            }
        }


    val output = this.rxCodec
            .output
            .compose(RxMonofier().composer)
            .filter { it is DataCodecEvent }
            .map { (it as DataCodecEvent).byteBuffer }
            .filter { it.remaining() > 0 }
}