package com.music.bitchord.cli.audio

data class PcmFormat(
    val sampleRate: Int = 44_100,
    val channels: Int = 2,
    val bytesPerSample: Int = 2,
    val isFloat: Boolean = false,
) {
    val frameBytes: Int get() = channels * bytesPerSample
    val byteRate: Int get() = sampleRate * frameBytes

    fun durationUsOf(bytes: Int): Long =
        if (byteRate == 0) 0L else bytes.toLong() * 1_000_000L / byteRate
}
