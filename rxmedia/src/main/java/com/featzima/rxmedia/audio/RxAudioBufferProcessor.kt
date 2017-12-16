package com.featzima.rxmedia.audio

import android.util.Log
import io.reactivex.subjects.BehaviorSubject
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.nio.ByteBuffer

abstract class RxAudioBufferProcessor() {

    abstract fun processBuffer(subscription: Subscription, buffer: ByteBuffer): Boolean

    private val emitterSubject = BehaviorSubject.create<Subscriber<in ByteBuffer>>()

    protected val emitter: Subscriber<in ByteBuffer>
        get() {
            return this.emitterSubject.blockingFirst()
        }

    val input: Subscriber<ByteBuffer> = object : Subscriber<ByteBuffer> {
        private lateinit var s: Subscription

        override fun onNext(t: ByteBuffer) {
            if (!processBuffer(s, t)) s.request(1)
        }

        override fun onSubscribe(s: Subscription) {
            this.s = s
            emitterSubject.subscribe { it.onSubscribe(s) }
        }

        override fun onError(t: Throwable) = emitterSubject.blockingFirst().onError(t)

        override fun onComplete() = emitterSubject.blockingFirst().onComplete()
    }

    val output: Publisher<ByteBuffer> = Publisher<ByteBuffer> { emitter ->
        emitterSubject.onNext(emitter)
    }

    class LoggingSubscription(private val s: Subscription) : Subscription {
        override fun cancel() {
            Log.e("LoggingSubscription", "cancel()")
            s.cancel()
        }

        override fun request(n: Long) {
            Log.e("LoggingSubscription", "request($n)")
            s.request(n)
        }

    }
}