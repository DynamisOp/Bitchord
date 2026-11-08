package com.music.bitchord.cli

import com.music.bitchord.cli.lyrics.CliLyrics
import com.music.bitchord.cli.player.CliPlayer
import com.music.bitchord.cli.ui.TuiRenderer
import com.music.bitchord.cli.ui.TuiView
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import kotlinx.coroutines.*
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import java.io.File
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Initialize InnerTubeX and StreamResolver caches
    val cacheDir = File(System.getProperty("user.home"), ".cache/bitchord-cli").apply { mkdirs() }
    val logFile = File(cacheDir, "player.log")
    TrackLog.echo = { level, tag, msg, err ->
        runCatching {
            logFile.appendText("[$level] $tag: $msg" + (err?.let { " - ${it.message}" } ?: "") + "\n")
        }
    }

    InnerTubeXResolver.init(
        filesDir = cacheDir,
        store = object : InnerTubeXResolver.ConfigStore {
            private val prefs = ConcurrentHashMap<String, String>()
            override fun getString(key: String): String? = prefs[key]
            override fun getLong(key: String): Long? = prefs[key]?.toLongOrNull()
            override fun putString(key: String, value: String) { prefs[key] = value }
            override fun putLong(key: String, value: Long) { prefs[key] = value.toString() }
        },
        poTokenProvider = null,
    )
    StreamResolver.maxKbps = { Int.MAX_VALUE }

    val terminal: Terminal = try {
        TerminalBuilder.builder()
            .system(true)
            .build()
    } catch (e: Exception) {
        System.err.println("Failed to initialize terminal: ${e.message}")
        exitProcess(1)
    }

    val prevAttributes = terminal.enterRawMode()
    val writer: PrintWriter = terminal.writer()

    // Enter alternate screen buffer, hide cursor, disable auto-wrap, clear screen & home cursor
    writer.print("\u001B[?1049h\u001B[?25l\u001B[?7l\u001B[2J\u001B[H")
    writer.flush()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val lyrics = CliLyrics(scope)
    val player = CliPlayer(scope, lyrics)
    val renderer = TuiRenderer(terminal, player, lyrics)

    fun restoreTerminal() {
        runCatching {
            // Restore cursor, re-enable auto-wrap, exit alternate screen buffer
            writer.print("\u001B[?25h\u001B[?7h\u001B[?1049l")
            writer.flush()
            terminal.attributes = prevAttributes
            terminal.close()
            player.close()
            scope.cancel()
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        restoreTerminal()
    })

    val reader = terminal.reader()
    var running = true
    val dirty = java.util.concurrent.atomic.AtomicBoolean(true)

    // Handle terminal resize (WINCH)
    runCatching {
        terminal.handle(Terminal.Signal.WINCH) {
            writer.print("\u001B[2J\u001B[H")
            writer.flush()
            dirty.set(true)
        }
    }

    // State change observers to trigger re-renders immediately
    scope.launch { player.positionMs.collect { dirty.set(true) } }
    scope.launch { player.statusMessage.collect { dirty.set(true) } }
    scope.launch { player.isPlaying.collect { dirty.set(true) } }
    scope.launch { lyrics.lines.collect { dirty.set(true) } }
    scope.launch { lyrics.currentIndex.collect { dirty.set(true) } }

    // Check if initial query passed via CLI arguments
    if (args.isNotEmpty()) {
        val initialQuery = args.joinToString(" ").trim()
        if (initialQuery.isNotBlank()) {
            renderer.searchQuery = initialQuery
            renderer.isSearchingNetwork = true
            scope.launch(Dispatchers.IO) {
                val results = runCatching {
                    YtMusicRepository.search(initialQuery, SearchFilter.SONGS)
                }.getOrNull()?.getOrNull()

                val tracks = results?.mapNotNull { item ->
                    when (item) {
                        is SearchResult.Track -> item.song
                        is SearchResult.TopTrack -> item.song
                        else -> null
                    }
                } ?: emptyList()

                renderer.searchResults = tracks
                renderer.selectedSearchIndex = 0
                renderer.isSearchingNetwork = false

                // If launched with query, auto-play first hit
                if (tracks.isNotEmpty()) {
                    player.playQueue(tracks, 0)
                }
            }
        }
    }

    try {
        var lastRenderTime = 0L

        while (running) {
            val now = System.currentTimeMillis()
            val isPlaying = player.isPlaying.value || player.isBuffering.value || renderer.isSearchingNetwork
            val renderInterval = if (isPlaying) 100L else 500L

            if (dirty.getAndSet(false) || (now - lastRenderTime >= renderInterval)) {
                renderer.render()
                lastRenderTime = now
            }

            // Non-blocking read with 50ms timeout
            val ch = reader.read(50)
            if (ch == -1) continue
            if (ch == -2) {
                // Timeout, no key pressed
                continue
            }

            dirty.set(true)

            if (renderer.isSearchingInput) {
                when (ch) {
                    27 -> { // Escape
                        renderer.isSearchingInput = false
                    }
                    10, 13 -> { // Enter
                        renderer.isSearchingInput = false
                        val query = renderer.searchQuery.trim()
                        if (query.isNotBlank()) {
                            renderer.isSearchingNetwork = true
                            renderer.currentView = TuiView.SEARCH
                            scope.launch(Dispatchers.IO) {
                                val results = runCatching {
                                    YtMusicRepository.search(query, SearchFilter.SONGS)
                                }.getOrNull()?.getOrNull()

                                val tracks = results?.mapNotNull { item ->
                                    when (item) {
                                        is SearchResult.Track -> item.song
                                        is SearchResult.TopTrack -> item.song
                                        else -> null
                                    }
                                } ?: emptyList()

                                renderer.searchResults = tracks
                                renderer.selectedSearchIndex = 0
                                renderer.isSearchingNetwork = false
                            }
                        }
                    }
                    8, 127 -> { // Backspace
                        if (renderer.searchQuery.isNotEmpty()) {
                            renderer.searchQuery = renderer.searchQuery.dropLast(1)
                        }
                    }
                    in 32..126 -> {
                        renderer.searchQuery += ch.toChar()
                    }
                }
            } else {
                when (ch) {
                    'q'.code, 3 -> { // 'q' or Ctrl+C
                        running = false
                    }
                    '/'.code -> {
                        renderer.isSearchingInput = true
                        renderer.searchQuery = ""
                        renderer.currentView = TuiView.SEARCH
                    }
                    ' '.code -> {
                        player.togglePlayPause()
                    }
                    9 -> { // Tab
                        renderer.currentView = if (renderer.currentView == TuiView.SEARCH) {
                            TuiView.QUEUE
                        } else {
                            TuiView.SEARCH
                        }
                    }
                    '1'.code -> {
                        renderer.currentView = TuiView.SEARCH
                    }
                    '2'.code -> {
                        renderer.currentView = TuiView.QUEUE
                    }
                    'j'.code -> {
                        if (renderer.currentView == TuiView.SEARCH) {
                            if (renderer.searchResults.isNotEmpty()) {
                                renderer.selectedSearchIndex =
                                    (renderer.selectedSearchIndex + 1).coerceAtMost(renderer.searchResults.size - 1)
                            }
                        } else {
                            if (player.queue.isNotEmpty()) {
                                renderer.selectedQueueIndex =
                                    (renderer.selectedQueueIndex + 1).coerceAtMost(player.queue.size - 1)
                            }
                        }
                    }
                    'k'.code -> {
                        if (renderer.currentView == TuiView.SEARCH) {
                            if (renderer.searchResults.isNotEmpty()) {
                                renderer.selectedSearchIndex =
                                    (renderer.selectedSearchIndex - 1).coerceAtLeast(0)
                            }
                        } else {
                            if (player.queue.isNotEmpty()) {
                                renderer.selectedQueueIndex =
                                    (renderer.selectedQueueIndex - 1).coerceAtLeast(0)
                            }
                        }
                    }
                    10, 13 -> { // Enter
                        if (renderer.currentView == TuiView.SEARCH) {
                            val selected = renderer.searchResults.getOrNull(renderer.selectedSearchIndex)
                            if (selected != null) {
                                // Play clicked track and queue the rest of the search results
                                player.playQueue(renderer.searchResults, renderer.selectedSearchIndex)
                            }
                        } else {
                            player.playIndex(renderer.selectedQueueIndex)
                        }
                    }
                    'a'.code -> { // Add to queue
                        if (renderer.currentView == TuiView.SEARCH) {
                            val selected = renderer.searchResults.getOrNull(renderer.selectedSearchIndex)
                            if (selected != null) {
                                player.addToQueue(selected)
                            }
                        }
                    }
                    'n'.code -> {
                        player.next()
                    }
                    'p'.code -> {
                        player.prev()
                    }
                    'h'.code -> {
                        player.seekBy(-5.0)
                    }
                    'l'.code -> {
                        player.seekBy(5.0)
                    }
                    '+'.code, '='.code -> {
                        player.volumeUp()
                    }
                    '-'.code, '_'.code -> {
                        player.volumeDown()
                    }
                    'c'.code -> { // Clear queue
                        val cur = player.currentTrack.value
                        player.queue.clear()
                        if (cur != null) {
                            player.queue.add(cur)
                            player.playIndex(0)
                        }
                    }
                    27 -> { // Escape sequence (e.g. arrow keys)
                        val next1 = reader.read(10)
                        if (next1 == '['.code) {
                            val next2 = reader.read(10)
                            when (next2) {
                                'A'.code -> { // Up arrow
                                    if (renderer.currentView == TuiView.SEARCH) {
                                        renderer.selectedSearchIndex = (renderer.selectedSearchIndex - 1).coerceAtLeast(0)
                                    } else {
                                        renderer.selectedQueueIndex = (renderer.selectedQueueIndex - 1).coerceAtLeast(0)
                                    }
                                }
                                'B'.code -> { // Down arrow
                                    if (renderer.currentView == TuiView.SEARCH) {
                                        if (renderer.searchResults.isNotEmpty()) {
                                            renderer.selectedSearchIndex = (renderer.selectedSearchIndex + 1).coerceAtMost(renderer.searchResults.size - 1)
                                        }
                                    } else {
                                        if (player.queue.isNotEmpty()) {
                                            renderer.selectedQueueIndex = (renderer.selectedQueueIndex + 1).coerceAtMost(player.queue.size - 1)
                                        }
                                    }
                                }
                                'C'.code -> { // Right arrow
                                    player.seekBy(5.0)
                                }
                                'D'.code -> { // Left arrow
                                    player.seekBy(-5.0)
                                }
                            }
                        }
                    }
                }
            }
        }
    } finally {
        restoreTerminal()
    }
}
