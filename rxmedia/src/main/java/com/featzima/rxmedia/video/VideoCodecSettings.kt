package com.featzima.rxmedia.video

import android.media.MediaFormat

data class VideoCodecSettings(
        val videoMime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
        val keyFrameInterval: Int = 10)
