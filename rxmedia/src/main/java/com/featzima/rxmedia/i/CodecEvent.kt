package com.featzima.rxmedia.i

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

sealed class CodecEvent<T>

data class FormatCodecEvent<T>(
        val mediaFormat: MediaFormat): CodecEvent<T>()

data class DataCodecEvent<T>(
        val data: T,
        val bufferInfo: MediaCodec.BufferInfo): CodecEvent<T>()