package com.music.bitchord.cli.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

class AudioSink {
    private var line: SourceDataLine? = null
    private var staging = ByteArray(0)

    var format: PcmFormat = PcmFormat(44_100, 2, 2)
        private set

    @Volatile
    var gain: Float = 0.8f // 80% default volume

    val isOpen: Boolean get() = line != null

    fun open(requested: PcmFormat): Result<PcmFormat> = runCatching {
        val targetFormat = requested.toAudioFormat()
        val info = DataLine.Info(SourceDataLine::class.java, targetFormat)
        val openedLine = AudioSystem.getLine(info) as SourceDataLine
        openedLine.open(targetFormat, requested.byteRate / 5) // ~200ms buffer
        openedLine.start()

        closeLine(line)
        line = openedLine
        format = requested
        requested
    }

    fun write(samples: FloatArray, count: Int): Int {
        val target = line ?: return 0
        if (count <= 0) return 0
        val bytes = convert(samples, count)
        return target.write(bytes, 0, count * format.bytesPerSample)
    }

    private fun convert(samples: FloatArray, count: Int): ByteArray {
        val current = format
        val volume = gain
        val bytes = count * current.bytesPerSample
        if (staging.size < bytes) staging = ByteArray(bytes)
        val out = staging

        if (current.isFloat) {
            for (index in 0 until count) {
                writeIntLe(out, index * 4, (samples[index] * volume).toRawBits())
            }
        } else {
            for (index in 0 until count) {
                val scaled = (samples[index] * volume).coerceIn(-1f, 1f)
                val value = (scaled * 32_767f).toInt()
                val at = index * 2
                out[at] = value.toByte()
                out[at + 1] = (value shr 8).toByte()
            }
        }
        return out
    }

    private fun writeIntLe(buffer: ByteArray, at: Int, value: Int) {
        buffer[at] = value.toByte()
        buffer[at + 1] = (value shr 8).toByte()
        buffer[at + 2] = (value shr 16).toByte()
        buffer[at + 3] = (value shr 24).toByte()
    }

    fun framesPlayed(): Long = line?.longFramePosition ?: 0L

    fun pause() {
        line?.stop()
    }

    fun resume() {
        line?.start()
    }

    fun flush() {
        line?.flush()
    }

    fun close() {
        val target = line ?: return
        line = null
        closeLine(target)
    }

    private fun closeLine(target: SourceDataLine?) {
        if (target == null) return
        runCatching {
            target.stop()
            target.flush()
            target.close()
        }
    }
}

fun PcmFormat.toAudioFormat(): AudioFormat = AudioFormat(
    if (isFloat) AudioFormat.Encoding.PCM_FLOAT else AudioFormat.Encoding.PCM_SIGNED,
    sampleRate.toFloat(),
    bytesPerSample * 8,
    channels,
    frameBytes,
    sampleRate.toFloat(),
    false,
)
