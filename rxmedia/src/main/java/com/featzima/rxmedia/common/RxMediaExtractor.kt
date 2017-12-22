package com.featzima.rxmedia.common

import android.content.res.AssetFileDescriptor
import android.media.MediaCodec
import android.media.MediaExtractor
import com.featzima.rxmedia.extensions.waitForRequested
import com.featzima.rxmedia.i.CodecEvent
import com.featzima.rxmedia.i.DataCodecEvent
import com.featzima.rxmedia.i.FormatCodecEvent
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import org.reactivestreams.Publisher
import java.nio.ByteBuffer

class RxMediaExtractor(
        val mediaExtractor: MediaExtractor,
        val trackSelector: ITrackSelector,
        val bufferSize: Int = 800000) {

    constructor(assetFd: AssetFileDescriptor, trackSelector: ITrackSelector) : this(MediaExtractor().apply {
        setDataSource(assetFd.fileDescriptor, assetFd.startOffset, assetFd.length)
    }, trackSelector)

    constructor(path: String, trackSelector: ITrackSelector) : this(MediaExtractor().apply {
        setDataSource(path)
    }, trackSelector)

    val output: Publisher<CodecEvent<ByteBuffer>> = Flowable.create<CodecEvent<ByteBuffer>>({ emitter ->
        var configured = false
        loop@ while (emitter.waitForRequested()) {
            if (!configured) {
                val trackId = this.trackSelector.selectTrackId(this.mediaExtractor)
                this.mediaExtractor.selectTrack(trackId)
                val mediaFormat = this.mediaExtractor.getTrackFormat(trackId)
                emitter.onNext(FormatCodecEvent(mediaFormat))
                configured = true
            } else {
                val buffer = ByteBuffer.allocate(this.bufferSize)
                if (this.mediaExtractor.readSampleData(buffer, 0) > 0) {
                    val info = MediaCodec.BufferInfo().apply {
                        presentationTimeUs = mediaExtractor.sampleTime
                        flags = mediaExtractor.sampleFlags
                    }
                    this.mediaExtractor.advance()
                    emitter.onNext(DataCodecEvent(buffer, info))
                } else break@loop
            }
        }
        emitter.onComplete()
    }, BackpressureStrategy.MISSING)
}