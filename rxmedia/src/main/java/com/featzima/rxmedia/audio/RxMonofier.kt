package com.featzima.rxmedia.audio

import com.featzima.rxmedia.i.CodecEvent
import com.featzima.rxmedia.i.DataCodecEvent
import com.featzima.rxmedia.i.FormatCodecEvent
import io.reactivex.FlowableTransformer
import java.nio.ByteBuffer

class RxMonofier {
    private var isStereo: Boolean = false

    val composer = FlowableTransformer<CodecEvent<ByteBuffer>, CodecEvent<ByteBuffer>> { upstream ->
        upstream
                .map {
                    when (it) {
                        is FormatCodecEvent -> {
                            isStereo = it.mediaFormat.getInteger("channel-count") == 2
                            it
                        }
                        is DataCodecEvent -> {
                            if (isStereo) {
                                DataCodecEvent(
                                        data = this.stereoBufferToMono(it.data),
                                        bufferInfo = it.bufferInfo)
                            } else {
                                it
                            }
                        }
                    }
                }
    }

    private fun stereoBufferToMono(buffer: ByteBuffer): ByteBuffer {
        val outputBuffer = ByteBuffer.allocate(buffer.remaining() / 2)
        for (i in 0 until buffer.remaining() / 2 step 2) {
            outputBuffer.put(i, buffer.get(i * 2))
            outputBuffer.put(i + 1, buffer.get(i * 2 + 1))
        }
        return outputBuffer
    }
}