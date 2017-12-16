package com.featzima.rxmedia.audio

import android.util.Log
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

class RxPcmMixer(
        leftOffsetUs: Long,
        rightOffsetUs: Long) {

    private val channelList = listOf<SourceChannel>(
            SourceChannel("left", leftOffsetUs),
            SourceChannel("right", rightOffsetUs))



    val leftInput = InputSubscriber(channelList[0])
    val rightInput = InputSubscriber(channelList[1])

    inner class InputSubscriber(
            private val sourceChannel: SourceChannel) : Subscriber<ByteBuffer> {

        private lateinit var s: Subscription

        override fun onNext(buffer: ByteBuffer) {
            synchronized(this@RxPcmMixer) {
                this.sourceChannel.queue(buffer)
            }
            this.s.request(1)
        }

        override fun onSubscribe(s: Subscription) {
            this.s = s
            this.s.request(1)
        }

        override fun onComplete() {
            synchronized(this@RxPcmMixer) {
                this.sourceChannel.complete()
            }
        }

        override fun onError(t: Throwable) {
        }
    }

    inner class SourceChannel(
            private val name: String,
            private val offsetUs: Long) {

        val bufferList = ConcurrentLinkedQueue<ByteBuffer>()
        var processed = 0L

        private var isCompleted = false

        fun isCompleted(): Boolean {
            return this.isCompleted && this.bufferList.isEmpty()
        }

        fun ready(): Boolean {
            return !bufferList.isEmpty() || isCompleted
        }

        fun readyForTimestamp(currentUs: Long): Boolean {
            return currentUs >= this.offsetUs
        }

        fun hasNextBuf(): Boolean {
            return !this.bufferList.isEmpty()
        }

        fun getNextBuffer(): ByteBuffer {
            return this.bufferList.peek()
        }

        fun releaseBuffer() {
            this.bufferList.poll()
        }

        fun queue(buffer: ByteBuffer) {
            processed += buffer.remaining()
            this.bufferList.add(buffer)
        }

        fun complete() {
            this.isCompleted = true
//            Log.e("!!!", "$name = $processed")
        }

    }

    private fun allChannelsReady(): Boolean {
        synchronized(this@RxPcmMixer) {
            return channelList.all { it.ready() }
        }
    }

    private var reallySended = 0L

    val output = Flowable.create<ByteBuffer>({ emitter ->
        var encoded = 0L
        var allCompleted = false
        while (!emitter.isCancelled && !allCompleted) {
            if (!allChannelsReady()) {
                Thread.sleep(200)
            }

            synchronized(this@RxPcmMixer) {
                val currentUs = PcmCalculator.presentationTimeByEncodedBytes(encoded)

                val readyChannels = channelList
                        .filter { it.hasNextBuf() }
                        .filter { it.readyForTimestamp(currentUs) }

                if (readyChannels.isEmpty()) return@synchronized

                val sourceBufferList = readyChannels.map { it.getNextBuffer() }.toList()
                val outSize = sourceBufferList.map { it.remaining() }.min()!!
                val outBuffer = ByteBuffer.allocate(outSize)

                val sourcePcmBufferList = sourceBufferList.map { it.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer() }
                val outPcmBuffer = outBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

                for (i in 0 until outPcmBuffer.limit()) {
                    val mixedSample = sourcePcmBufferList.sumBy { it[i].toInt() }.toShort()
                    outPcmBuffer.put(i, mixedSample)
                }

                encoded += outSize
                sourceBufferList.forEach { it.position(it.position() + outSize) }

                (0 until sourceBufferList.size)
                        .filter { sourceBufferList[it].remaining() == 0 }
                        .forEach { readyChannels[it].releaseBuffer() }

                reallySended += outBuffer.remaining()
                emitter.onNext(outBuffer)

                if (channelList.all { it.isCompleted() }) {
                    emitter.onComplete()
                    allCompleted = true
                    Log.e("!!!", "total : $encoded, really = $reallySended")
                }
            }
        }
    }, BackpressureStrategy.BUFFER)

}