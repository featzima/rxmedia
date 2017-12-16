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

    private var videoTimestamp = 0L
    private var audioTimestamp = 0L

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

        override fun onNext(event: CodecEvent<ByteBuffer>) {
            when (event) {
                is FormatCodecEvent -> {
                    synchronized(this@RxMuxer) {
                        this.trackIndex = muxer.addTrack(event.mediaFormat)
                        this@RxMuxer.startMuxer()
                    }
                }
                is DataCodecEvent -> {
                    Log.d(TAG, "onNext.startonNext.start($codec, $event, ${event.bufferInfo.presentationTimeUs})")
                    try {
                        synchronized(this@RxMuxer) {
                            muxer.writeSampleData(this.trackIndex, event.data, event.bufferInfo)
                        }
                        this.subscription.request(1)
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }
            }
//            Log.d(TAG, "onNext.end($codec, $event)")
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