package com.featzima.rxmedia.extensions

import android.util.Log
import io.reactivex.Flowable
import io.reactivex.FlowableTransformer
import io.reactivex.schedulers.Schedulers
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber

infix fun <T> Publisher<T>.streamTo(input: Subscriber<T>) {
    Flowable.fromPublisher<T>(this)
            .subscribeOn(Schedulers.io(), false)
            .subscribe(input)
}

fun <T> Publisher<T>.loggedStreamTo(input: Subscriber<T>, label: String) {
    Flowable.fromPublisher<T>(this)
            .subscribeOn(Schedulers.io(), false)
            .compose(RxLogger("loggedStreamTo", label))
            .subscribe(input)
}

class RxLogger<T>(private val tag: String, private val label: String) : FlowableTransformer<T, T> {
    override fun apply(upstream: Flowable<T>): Publisher<T> {
        return upstream
                .doOnSubscribe { Log.e("$tag $label", "doOnSubscribe") }
                .doOnRequest { Log.e("$tag $label", "doOnRequest($it)") }
                .doOnNext { Log.e("$tag $label", "doOnNext") }
                .doOnComplete { Log.e("$tag $label", "doOnComplete") }
                .doOnCancel { Log.e("$tag $label", "doOnCancel") }
    }
}