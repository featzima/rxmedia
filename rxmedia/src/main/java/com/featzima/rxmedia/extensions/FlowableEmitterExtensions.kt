package com.featzima.rxmedia.extensions

import io.reactivex.FlowableEmitter

fun <T> FlowableEmitter<T>.waitForRequested(timeoutMs: Long = 200L) {
    while(this.requested() == 0L && !this.isCancelled) {
//        Thread.sleep(timeoutMs)
    }
}
