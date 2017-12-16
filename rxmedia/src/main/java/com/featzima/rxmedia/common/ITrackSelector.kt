package com.featzima.rxmedia.common

import android.media.MediaExtractor

interface ITrackSelector {
    fun selectTrackId(mediaExtractor: MediaExtractor): Int
}