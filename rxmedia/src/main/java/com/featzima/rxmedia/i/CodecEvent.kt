package com.featzima.rxmedia.i

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

sealed class CodecEvent

data class FormatCodecEvent(
        val mediaFormat: MediaFormat): CodecEvent()

data class DataCodecEvent(
        val byteBuffer: ByteBuffer,
        val bufferInfo: MediaCodec.BufferInfo): CodecEvent()