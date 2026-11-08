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
import kotlin.math.sqrt

enum class CliRepeatMode(val label: String) {
    OFF("Off"),
    ALL("All"),
    ONE("One"),
}

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

    private val _streamInfo = MutableStateFlow<String?>(null)
    val streamInfo: StateFlow<String?> = _streamInfo.asStateFlow()

    private val _repeatMode = MutableStateFlow(CliRepeatMode.OFF)
    val repeatMode: StateFlow<CliRepeatMode> = _repeatMode.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    // 12-band audio visualizer levels in [0.0, 1.0]
    private val currentBands = FloatArray(12)
    private val _visualizerBands = MutableStateFlow(FloatArray(12))
    val visualizerBands: StateFlow<FloatArray> = _visualizerBands.asStateFlow()

    val queue = CopyOnWriteArrayList<Song>()
    private val originalQueue = ArrayList<Song>()
    var queueIndex: Int = -1
        private set

    private var playJob: Job? = null
    private val seekTargetMs = AtomicLong(-1L)
    private val stopRequested = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var preMuteVolume = 0.8f

    init {
        sink.gain = 0.8f
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        queue.clear()
        queue.addAll(songs)
        originalQueue.clear()
        originalQueue.addAll(songs)
        _isShuffle.value = false
        if (queue.isNotEmpty()) {
            val idx = startIndex.coerceIn(0, queue.size - 1)
            playIndex(idx)
        }
    }

    fun addToQueue(song: Song) {
        queue.add(song)
        originalQueue.add(song)
        if (_currentTrack.value == null) {
            playIndex(queue.size - 1)
        }
    }

    fun addAllToQueue(songs: List<Song>) {
        queue.addAll(songs)
        originalQueue.addAll(songs)
        if (_currentTrack.value == null && queue.isNotEmpty()) {
            playIndex(queue.size - songs.size)
        }
    }

    fun playTrack(song: Song) {
        val existingIdx = queue.indexOfFirst { it.videoId == song.videoId }
        if (existingIdx >= 0) {
            playIndex(existingIdx)
        } else {
            queue.add(song)
            originalQueue.add(song)
            playIndex(queue.size - 1)
        }
    }

    fun playIndex(index: Int) {
        if (index !in queue.indices) return
        queueIndex = index
        val song = queue[index]
        startPlayback(song)
    }

    fun cycleRepeatMode(): CliRepeatMode {
        val next = when (_repeatMode.value) {
            CliRepeatMode.OFF -> CliRepeatMode.ALL
            CliRepeatMode.ALL -> CliRepeatMode.ONE
            CliRepeatMode.ONE -> CliRepeatMode.OFF
        }
        _repeatMode.value = next
        return next
    }

    fun toggleShuffle(): Boolean {
        val newShuffle = !_isShuffle.value
        _isShuffle.value = newShuffle
        val current = currentTrack.value
        if (newShuffle) {
            if (originalQueue.isEmpty() && queue.isNotEmpty()) {
                originalQueue.addAll(queue)
            }
            val remaining = queue.filter { it.videoId != current?.videoId }.shuffled()
            queue.clear()
            if (current != null) queue.add(current)
            queue.addAll(remaining)
            queueIndex = if (current != null) 0 else -1
        } else {
            if (originalQueue.isNotEmpty()) {
                queue.clear()
                queue.addAll(originalQueue)
                queueIndex = queue.indexOfFirst { it.videoId == current?.videoId }.coerceAtLeast(0)
            }
        }
        return newShuffle
    }

    fun toggleMute(): Boolean {
        val nowMuted = !_isMuted.value
        _isMuted.value = nowMuted
        if (nowMuted) {
            preMuteVolume = _volume.value
            sink.gain = 0f
            _volume.value = 0f
        } else {
            setVolume(if (preMuteVolume > 0f) preMuteVolume else 0.8f)
        }
        return nowMuted
    }

    fun setStatus(msg: String?) {
        _statusMessage.value = msg
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
                } catch (e: Throwable) {
                    _statusMessage.value = "Failed to resolve: ${e.message ?: e.javaClass.simpleName}"
                    _isBuffering.value = false
                    return@launch
                }

                if (stopRequested.get()) return@launch

                _statusMessage.value = "Buffering ${song.title}..."
                val newDecoder = AudioDecoder()
                activeDecoder = newDecoder
                decoder = newDecoder

                val headers = StreamResolver.mediaHeadersFor(streamUrl)
                val openRes = newDecoder.open(streamUrl, headers)
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
                val sampleRateKhz = newDecoder.outputFormat.sampleRate / 1000
                _streamInfo.value = "OPUS · ${sampleRateKhz}kHz Stereo"

                lyrics.loadFor(song, finalDuration)

                // Decoding & playback loop
                while (!stopRequested.get()) {
                    if (isPaused.get()) {
                        sink.pause()
                        clearVisualizer()
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
                    updateVisualizer(samples)

                    val curPosMs = newDecoder.positionUs / 1000
                    _positionMs.value = curPosMs
                    lyrics.updatePosition(curPosMs)
                }

                clearVisualizer()

                // If finished naturally without manual stop, advance according to repeat mode
                if (!stopRequested.get()) {
                    when (_repeatMode.value) {
                        CliRepeatMode.ONE -> playIndex(queueIndex)
                        CliRepeatMode.ALL -> {
                            val nextIdx = queueIndex + 1
                            if (nextIdx in queue.indices) playIndex(nextIdx) else playIndex(0)
                        }
                        CliRepeatMode.OFF -> next()
                    }
                }
            } catch (e: CancellationException) {
                // Task cancelled cleanly
            } catch (e: Throwable) {
                _statusMessage.value = "Playback error: ${e.message ?: e.javaClass.simpleName}"
                _isBuffering.value = false
                _isPlaying.value = false
                clearVisualizer()
            } finally {
                activeDecoder?.close()
                if (decoder == activeDecoder) decoder = null
            }
        }
    }

    private fun updateVisualizer(samples: FloatArray) {
        val step = samples.size / 12
        if (step <= 0) return
        val bands = FloatArray(12)
        for (b in 0 until 12) {
            var sum = 0f
            val start = b * step
            val end = minOf(start + step, samples.size)
            for (i in start until end) {
                val s = samples[i]
                sum += s * s
            }
            val rms = sqrt(sum / (end - start).coerceAtLeast(1))
            val target = (rms * 3.5f).coerceIn(0f, 1f)
            currentBands[b] = currentBands[b] * 0.65f + target * 0.35f
            bands[b] = currentBands[b]
        }
        _visualizerBands.value = bands
    }

    private fun clearVisualizer() {
        for (i in currentBands.indices) currentBands[i] = 0f
        _visualizerBands.value = FloatArray(12)
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
            clearVisualizer()
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
        } else if (_repeatMode.value == CliRepeatMode.ALL) {
            playIndex(0)
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
        if (_isMuted.value) _isMuted.value = false
        setVolume((_volume.value + step).coerceIn(0f, 1f))
    }

    fun volumeDown(step: Float = 0.05f) {
        if (_isMuted.value) _isMuted.value = false
        setVolume((_volume.value - step).coerceIn(0f, 1f))
    }

    fun setVolume(vol: Float) {
        val clamped = vol.coerceIn(0f, 1f)
        _volume.value = clamped
        if (!_isMuted.value) {
            sink.gain = clamped
        }
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
        clearVisualizer()
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
