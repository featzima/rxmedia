package com.featzima.rxmedia.muxer

import android.media.MediaMuxer
import android.util.Log
import com.featzima.rxmedia.i.*
import io.reactivex.Flowable
import io.reactivex.FlowableSubscriber
import io.reactivex.schedulers.Schedulers
import org.reactivestreams.Subscription
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class RxMuxer(
        outputFile: String,
        private val completionListener: () -> Unit) : IRxMuxer {

    private var codecs = mutableListOf<IRxCodec>()
    private val muxer: MediaMuxer = MediaMuxer(outputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val subscriptions = mutableListOf<Subscription>()
    private val codecsStarted = AtomicInteger()
    private val codecsStopped = AtomicInteger()

    override fun registerCodec(codec: IRxCodec) {
        Log.d(TAG, "registerCodec($codec)")
        this.codecs.add(codec)
    }

    fun start() {
        Log.d(TAG, "start()")
        this.codecs.forEach { codec ->
            Flowable.fromPublisher<CodecEvent<ByteBuffer>>(codec.output())
                    .subscribeOn(Schedulers.io())
                    .subscribe(CodecSubscriber(codec))
        }
    }

    private fun startMuxer() {
        if (this.codecsStarted.incrementAndGet() == this.codecs.size) {
            Log.d(TAG, "startMuxer()")
            this.muxer.start()
            this.subscriptions.forEach {
                Log.d(TAG, "request(1, $it)")
                it.request(1)
            }
        }
    }

    private fun stopMuxer() {
        if (this.codecsStopped.incrementAndGet() == this.codecs.size) {
            Log.d(TAG, "stopMuxer()")
            this.subscriptions.forEach {
                Log.d(TAG, "cancel($it)")
                it.cancel()
            }
            this.muxer.stop()
            this.completionListener()
        }
    }

    inner class CodecSubscriber(
            private val codec: IRxCodec) : FlowableSubscriber<CodecEvent<ByteBuffer>> {

        private lateinit var subscription: Subscription
        private var trackIndex: Int = -1

        override fun onError(t: Throwable) {
            Log.e(TAG, "onError($codec, $t)")
        }

        override fun onComplete() {
            Log.d(TAG, "onComplete($codec)")
            this@RxMuxer.stopMuxer()
        }

        private var formatted = false
        override fun onNext(event: CodecEvent<ByteBuffer>) {
            when (event) {
                is FormatCodecEvent -> {
                    if (!formatted) {
                        Log.e(TAG, "FormatCodecEvent")
                        synchronized(this@RxMuxer) {
                            this.trackIndex = muxer.addTrack(event.mediaFormat)
                            this@RxMuxer.startMuxer()
                        }
                        formatted = true
                    }
                }
                is DataCodecEvent -> {
                    Log.d(TAG, "onNext.start($codec, $event, ${event.bufferInfo.presentationTimeUs})")
                    try {
                        synchronized(this@RxMuxer) {
                            muxer.writeSampleData(this.trackIndex, event.data, event.bufferInfo)
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "onNext()", e)
                    }
                }
            }
            this.subscription.request(1)
        }

        override fun onSubscribe(subscription: Subscription) {
            this.subscription = subscription
            Log.d(TAG, "onSubscribe::request(1, $codec)")
            this.subscription.request(1)
            synchronized(this@RxMuxer) {
                subscriptions.add(subscription)
            }
        }

    }

    companion object {
        val TAG = RxMuxer::class.java.simpleName
    }
}