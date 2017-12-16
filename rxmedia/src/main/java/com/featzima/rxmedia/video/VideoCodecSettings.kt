package com.featzima.rxmedia.video

import android.media.MediaFormat

data class VideoCodecSettings(
        val width: Int,
        val height: Int,
        val frameRate: Int = 30,
        val videoMime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
        val keyFrameInterval: Int = 10)
