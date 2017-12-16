package com.featzima.rxmedia.common

import android.media.MediaExtractor
import android.media.MediaFormat
import com.featzima.rxmedia.exceptions.TrackSelectionException

class MimeTrackSelector(
        val mime: String) : ITrackSelector {

    override fun selectTrackId(mediaExtractor: MediaExtractor): Int {
        for (trackId in 0 until mediaExtractor.trackCount) {
            val format = mediaExtractor.getTrackFormat(trackId)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime.startsWith(this.mime)) {
                mediaExtractor.selectTrack(trackId)
                return trackId
            }
        }
        throw TrackSelectionException()
    }
}