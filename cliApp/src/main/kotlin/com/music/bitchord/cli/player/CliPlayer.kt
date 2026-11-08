package com.music.bitchord.cli.player

import com.music.bitchord.cli.audio.AudioDecoder
import com.music.bitchord.cli.audio.AudioSink
import com.music.bitchord.cli.lyrics.CliLyrics
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class CliPlayer(
    private val scope: CoroutineScope,
    val lyrics: CliLyrics,
) : AutoCloseable {

    private val sink = AudioSink()
    private var decoder: AudioDecoder? = null

    private val _currentTrack = MutableStateFlow<Song?>(null)
    val currentTrack: StateFlow<Song?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _volume = MutableStateFlow(0.8f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    val queue = CopyOnWriteArrayList<Song>()
    var queueIndex: Int = -1
        private set

    private var playJob: Job? = null
    private val seekTargetMs = AtomicLong(-1L)
    private val stopRequested = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    init {
        sink.gain = 0.8f
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        queue.clear()
        queue.addAll(songs)
        if (queue.isNotEmpty()) {
            val idx = startIndex.coerceIn(0, queue.size - 1)
            playIndex(idx)
        }
    }

    fun addToQueue(song: Song) {
        queue.add(song)
        if (_currentTrack.value == null) {
            playIndex(queue.size - 1)
        }
    }

    fun playTrack(song: Song) {
        val existingIdx = queue.indexOfFirst { it.videoId == song.videoId }
        if (existingIdx >= 0) {
            playIndex(existingIdx)
        } else {
            queue.add(song)
            playIndex(queue.size - 1)
        }
    }

    fun playIndex(index: Int) {
        if (index !in queue.indices) return
        queueIndex = index
        val song = queue[index]
        startPlayback(song)
    }

    private fun startPlayback(song: Song) {
        stopPlayback()
        _currentTrack.value = song
        _isBuffering.value = true
        _statusMessage.value = "Resolving audio stream..."
        _positionMs.value = 0L
        val durTextMs = parseDurationMs(song.durationText)
        _durationMs.value = durTextMs
        isPaused.set(false)
        _isPlaying.value = false
        stopRequested.set(false)
        seekTargetMs.set(-1L)

        playJob = scope.launch(Dispatchers.IO) {
            var activeDecoder: AudioDecoder? = null
            try {
                val streamUrl = try {
                    StreamResolver.resolve(song.videoId)
                } catch (e: Exception) {
                    _statusMessage.value = "Failed to resolve stream: ${e.message}"
                    _isBuffering.value = false
                    return@launch
                }

                if (stopRequested.get()) return@launch

                _statusMessage.value = "Buffering ${song.title}..."
                val newDecoder = AudioDecoder()
                activeDecoder = newDecoder
                decoder = newDecoder

                val openRes = newDecoder.open(streamUrl)
                if (openRes.isFailure) {
                    _statusMessage.value = "Decoder error: ${openRes.exceptionOrNull()?.message}"
                    _isBuffering.value = false
                    return@launch
                }

                if (stopRequested.get()) {
                    newDecoder.close()
                    return@launch
                }

                val sinkRes = sink.open(newDecoder.outputFormat)
                if (sinkRes.isFailure) {
                    _statusMessage.value = "Audio device error: ${sinkRes.exceptionOrNull()?.message}"
                    _isBuffering.value = false
                    return@launch
                }

                val decDurMs = newDecoder.durationUs / 1000
                val finalDuration = if (decDurMs > 0) decDurMs else durTextMs
                _durationMs.value = finalDuration

                _isBuffering.value = false
                _isPlaying.value = true
                _statusMessage.value = null

                lyrics.loadFor(song, finalDuration)

                // Decoding & playback loop
                while (!stopRequested.get()) {
                    if (isPaused.get()) {
                        sink.pause()
                        delay(40)
                        continue
                    } else {
                        sink.resume()
                    }

                    val seekTo = seekTargetMs.getAndSet(-1L)
                    if (seekTo >= 0) {
                        newDecoder.seekTo(seekTo / 1000.0)
                        sink.flush()
                        _positionMs.value = seekTo
                        lyrics.updatePosition(seekTo)
                    }

                    val samples = newDecoder.readSamples()
                    if (samples == null) {
                        // EOF reached naturally
                        break
                    }

                    sink.write(samples, samples.size)

                    val curPosMs = newDecoder.positionUs / 1000
                    _positionMs.value = curPosMs
                    lyrics.updatePosition(curPosMs)
                }

                // If finished naturally without manual stop, advance to next
                if (!stopRequested.get()) {
                    next()
                }
            } catch (e: CancellationException) {
                // Task cancelled cleanly
            } catch (e: Exception) {
                _statusMessage.value = "Playback error: ${e.message}"
            } finally {
                activeDecoder?.close()
                if (decoder == activeDecoder) decoder = null
            }
        }
    }

    fun togglePlayPause() {
        if (_currentTrack.value == null) return
        val currentlyPaused = isPaused.get()
        if (currentlyPaused) {
            isPaused.set(false)
            _isPlaying.value = true
            sink.resume()
        } else {
            isPaused.set(true)
            _isPlaying.value = false
            sink.pause()
        }
    }

    fun seekBy(seconds: Double) {
        val cur = _positionMs.value
        val dur = _durationMs.value
        val target = ((cur / 1000.0) + seconds).coerceIn(0.0, if (dur > 0) dur / 1000.0 else 3600.0)
        seekTargetMs.set((target * 1000).toLong())
    }

    fun next() {
        if (queue.isEmpty()) return
        val nextIdx = queueIndex + 1
        if (nextIdx in queue.indices) {
            playIndex(nextIdx)
        } else {
            stopPlayback()
            _statusMessage.value = "End of queue"
        }
    }

    fun prev() {
        if (queue.isEmpty()) return
        // If > 3 seconds in, restart track
        if (_positionMs.value > 3000) {
            seekTargetMs.set(0L)
            return
        }
        val prevIdx = queueIndex - 1
        if (prevIdx in queue.indices) {
            playIndex(prevIdx)
        } else {
            seekTargetMs.set(0L)
        }
    }

    fun volumeUp(step: Float = 0.05f) {
        setVolume((_volume.value + step).coerceIn(0f, 1f))
    }

    fun volumeDown(step: Float = 0.05f) {
        setVolume((_volume.value - step).coerceIn(0f, 1f))
    }

    fun setVolume(vol: Float) {
        val clamped = vol.coerceIn(0f, 1f)
        _volume.value = clamped
        sink.gain = clamped
    }

    fun stopPlayback() {
        stopRequested.set(true)
        playJob?.cancel()
        playJob = null
        sink.flush()
        sink.close()
        decoder?.close()
        decoder = null
        _isPlaying.value = false
        _isBuffering.value = false
    }

    override fun close() {
        stopPlayback()
        lyrics.clear()
    }

    companion object {
        fun parseDurationMs(text: String?): Long {
            if (text.isNullOrBlank()) return 0L
            val parts = text.trim().split(":").mapNotNull { it.toLongOrNull() }
            return when (parts.size) {
                1 -> parts[0] * 1000L
                2 -> (parts[0] * 60 + parts[1]) * 1000L
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
                else -> 0L
            }
        }
    }
}
