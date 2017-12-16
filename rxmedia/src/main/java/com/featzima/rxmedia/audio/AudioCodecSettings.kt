package com.featzima.rxmedia.audio

import android.media.MediaFormat

data class AudioCodecSettings(
        val sampleRate: Int = 44100,
        val channelsCount: Int = 2,
        val audioMime: String = MediaFormat.MIMETYPE_AUDIO_AAC)