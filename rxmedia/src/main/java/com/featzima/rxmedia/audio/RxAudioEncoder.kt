package com.featzima.rxmedia.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.featzima.rxmedia.i.CodecEvent
import com.featzima.rxmedia.i.IRxAudioCodec
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import java.nio.ByteBuffer

class RxAudioEncoder(
        private val settings: AudioCodecSettings) : IRxAudioCodec {

    private val rxCodec: RxEncodeCodec

    init {
        val format = MediaFormat()
        format.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm")
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, 44100)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128000)//AAC-HE 64kbps
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)

        val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
        codec.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        this.rxCodec = RxEncodeCodec(
                "Audio.Encoder",
                codec = codec,
                presentationTimeCalculator = { encodedBytes -> 1000000L * (encodedBytes / 2) / 44100 })
    }

    val input: Subscriber<ByteBuffer> = this.rxCodec.input

    override fun output(): Publisher<CodecEvent> = this.rxCodec.output

    companion object {
        private val TAG = RxAudioEncoder::class.java.simpleName
    }
}