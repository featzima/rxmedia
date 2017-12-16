package com.featzima.rxmedia.audio

class PcmCalculator private constructor() {
    companion object {
        val presentationTimeByEncodedBytes = { encodedBytes: Long -> 1000000L * (encodedBytes / 2) / 44100 }
    }
}