package com.featzima.rxmedia.audio

import android.media.MediaCodec
import android.media.MediaFormat
import com.featzima.rxmedia.i.CodecEvent
import com.featzima.rxmedia.i.DataCodecEvent
import com.featzima.rxmedia.i.FormatCodecEvent
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.nio.ByteBuffer


class RxAudioDecoder {
    private val rxCodec = RxCodec(
            TAG = "Audio.Decoder",
            presentationTimeCalculator = { encodedBytes -> 1000000L * (encodedBytes / 2) / 44100 })

    val input: Subscriber<CodecEvent<ByteBuffer>>
        get() = object : Subscriber<CodecEvent<ByteBuffer>> {
            lateinit var s: Subscription
            override fun onNext(event: CodecEvent<ByteBuffer>) {
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
                        rxCodec.input.onNext(event.data)
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
            .filter { it is DataCodecEvent<ByteBuffer> }
            .map { (it as DataCodecEvent<ByteBuffer>).data }
            .filter { it.remaining() > 0 }
}