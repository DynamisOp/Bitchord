package com.music.bitchord.cli.lyrics

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LrcLib
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CliLyrics(private val scope: CoroutineScope) {

    private val _lines = MutableStateFlow<List<LyricLine>>(emptyList())
    val lines: StateFlow<List<LyricLine>> = _lines.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var currentSongId: String? = null
    private var fetchJob: Job? = null

    fun loadFor(song: Song, durationMs: Long) {
        if (currentSongId == song.videoId && _lines.value.isNotEmpty()) return
        currentSongId = song.videoId
        fetchJob?.cancel()
        _lines.value = emptyList()
        _currentIndex.value = -1
        _isLoading.value = true

        fetchJob = scope.launch(Dispatchers.IO) {
            val fetched = runCatching {
                LyricsRepository.lyrics(
                    videoId = song.videoId,
                    title = song.title,
                    artist = song.artist,
                    durationMs = durationMs,
                )?.lines
            }.getOrNull() ?: runCatching {
                LrcLib.lyrics(
                    title = song.title,
                    artist = song.artist,
                    durationMs = durationMs,
                )
            }.getOrNull()

            if (currentSongId == song.videoId) {
                _lines.value = fetched?.filter { it.text.isNotBlank() } ?: emptyList()
                _isLoading.value = false
            }
        }
    }

    fun updatePosition(posMs: Long) {
        val all = _lines.value
        if (all.isEmpty()) {
            if (_currentIndex.value != -1) _currentIndex.value = -1
            return
        }

        var idx = -1
        for (i in all.indices) {
            if (all[i].timeMs <= posMs) {
                idx = i
            } else {
                break
            }
        }
        if (_currentIndex.value != idx) {
            _currentIndex.value = idx
        }
    }

    fun clear() {
        fetchJob?.cancel()
        currentSongId = null
        _lines.value = emptyList()
        _currentIndex.value = -1
        _isLoading.value = false
    }
}
