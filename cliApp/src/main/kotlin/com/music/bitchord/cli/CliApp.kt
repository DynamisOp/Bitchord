package com.music.bitchord.cli

import com.music.bitchord.cli.lyrics.CliLyrics
import com.music.bitchord.cli.player.CliPlayer
import com.music.bitchord.cli.ui.TuiLayout
import com.music.bitchord.cli.ui.TuiRenderer
import com.music.bitchord.cli.ui.TuiView
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
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
    scope.launch { player.repeatMode.collect { dirty.set(true) } }
    scope.launch { player.isShuffle.collect { dirty.set(true) } }
    scope.launch { player.isMuted.collect { dirty.set(true) } }
    scope.launch { player.visualizerBands.collect { dirty.set(true) } }
    scope.launch { lyrics.lines.collect { dirty.set(true) } }
    scope.launch { lyrics.currentIndex.collect { dirty.set(true) } }
    scope.launch { lyrics.offsetText.collect { dirty.set(true) } }

    fun performSearchOrLoad(input: String, autoPlay: Boolean = true) {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return

        // 1. YouTube playlist URL or ID detection
        val playlistRegex = "[?&]list=([a-zA-Z0-9_-]+)".toRegex()
        val matchPlaylist = playlistRegex.find(trimmed)
        val playlistId = matchPlaylist?.groupValues?.get(1)
            ?: if (trimmed.startsWith("PL") || trimmed.startsWith("RDCLAK") || trimmed.startsWith("OLAK5uy_")) trimmed else null

        if (playlistId != null) {
            val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
            renderer.searchQuery = "Loading playlist $playlistId..."
            renderer.isSearchingNetwork = true
            player.setStatus("Loading playlist: $playlistId...")
            scope.launch(Dispatchers.IO) {
                val songs = runCatching {
                    YtMusicRepository.allSongs(browseId).getOrThrow()
                }.getOrNull()

                renderer.isSearchingNetwork = false
                if (!songs.isNullOrEmpty()) {
                    renderer.searchResults = songs
                    renderer.selectedSearchIndex = 0
                    renderer.currentView = TuiView.QUEUE
                    if (autoPlay) {
                        player.playQueue(songs, 0)
                    } else {
                        player.addAllToQueue(songs)
                    }
                    player.setStatus("Loaded playlist (${songs.size} tracks)")
                } else {
                    player.setStatus("Failed to load playlist (empty or private)")
                }
            }
            return
        }

        // 2. Direct video URL detection
        val videoRegex = "(?:v=|/v/|youtu\\.be/|/shorts/)([a-zA-Z0-9_-]{11})".toRegex()
        val matchVideo = videoRegex.find(trimmed)
        if (matchVideo != null) {
            val videoId = matchVideo.groupValues[1]
            renderer.isSearchingNetwork = true
            renderer.searchQuery = "Loading track $videoId..."
            player.setStatus("Loading YouTube track...")
            scope.launch(Dispatchers.IO) {
                val song = runCatching {
                    YtMusicRepository.trackLinks(videoId).getOrThrow()
                }.getOrNull() ?: Song(
                    videoId = videoId,
                    title = "YouTube Track $videoId",
                    artist = "YouTube",
                    thumbnailUrl = null,
                )

                renderer.isSearchingNetwork = false
                renderer.searchResults = listOf(song)
                renderer.selectedSearchIndex = 0
                if (autoPlay) {
                    player.playQueue(listOf(song), 0)
                } else {
                    player.addToQueue(song)
                }
            }
            return
        }

        // 3. Regular search or playlist search via "p:" / "playlist:"
        val isPlaylistSearch = trimmed.startsWith("p:", ignoreCase = true) || trimmed.startsWith("playlist:", ignoreCase = true)
        val actualQuery = if (isPlaylistSearch) trimmed.substringAfter(":").trim() else trimmed

        renderer.isSearchingNetwork = true
        renderer.currentView = TuiView.SEARCH
        scope.launch(Dispatchers.IO) {
            val results = runCatching {
                val filter = if (isPlaylistSearch) SearchFilter.PLAYLISTS else SearchFilter.SONGS
                YtMusicRepository.search(actualQuery, filter)
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

            if (autoPlay && tracks.isNotEmpty() && !isPlaylistSearch) {
                player.playQueue(tracks, 0)
            }
        }
    }

    // Check if initial query or playlist URL passed via CLI arguments
    if (args.isNotEmpty()) {
        val initialInput = args.joinToString(" ").trim()
        if (initialInput.isNotBlank()) {
            renderer.searchQuery = initialInput
            performSearchOrLoad(initialInput, autoPlay = true)
        }
    }

    try {
        var lastRenderTime = 0L

        while (running) {
            val now = System.currentTimeMillis()
            val isPlaying = player.isPlaying.value || player.isBuffering.value || renderer.isSearchingNetwork
            val renderInterval = if (isPlaying) 80L else 500L

            if (dirty.getAndSet(false) || (now - lastRenderTime >= renderInterval)) {
                renderer.render()
                lastRenderTime = now
            }

            // Non-blocking read with 40ms timeout
            val ch = reader.read(40)
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
                        performSearchOrLoad(query, autoPlay = true)
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
                    'f'.code -> { // Toggle favorite on playing track or selected item
                        val curTrack = player.currentTrack.value
                        val targetSong = when {
                            curTrack != null -> curTrack
                            renderer.currentView == TuiView.SEARCH -> renderer.searchResults.getOrNull(renderer.selectedSearchIndex)
                            renderer.currentView == TuiView.QUEUE -> player.queue.getOrNull(renderer.selectedQueueIndex)
                            renderer.currentView == TuiView.FAVORITES -> CliFavorites.getFavorites().getOrNull(renderer.selectedFavoritesIndex)
                            else -> null
                        }
                        if (targetSong != null) {
                            val added = CliFavorites.toggleFavorite(targetSong)
                            val shortTitle = targetSong.title.take(24)
                            val msg = if (added) "♥ Added to Favorites: $shortTitle" else "♡ Removed from Favorites: $shortTitle"
                            player.setStatus(msg)
                            if (renderer.currentView == TuiView.FAVORITES && !added) {
                                val count = CliFavorites.count()
                                if (renderer.selectedFavoritesIndex >= count) {
                                    renderer.selectedFavoritesIndex = (count - 1).coerceAtLeast(0)
                                }
                            }
                        }
                    }
                    'd'.code, 'x'.code -> { // Remove from favorites
                        if (renderer.currentView == TuiView.FAVORITES) {
                            val favs = CliFavorites.getFavorites()
                            val song = favs.getOrNull(renderer.selectedFavoritesIndex)
                            if (song != null) {
                                CliFavorites.removeFavorite(song.videoId)
                                val shortTitle = song.title.take(24)
                                player.setStatus("♡ Removed from Favorites: $shortTitle")
                                val count = CliFavorites.count()
                                if (renderer.selectedFavoritesIndex >= count) {
                                    renderer.selectedFavoritesIndex = (count - 1).coerceAtLeast(0)
                                }
                            }
                        }
                    }
                    'z'.code -> { // Toggle queue minimization / Zen lyrics mode
                        val isZen = renderer.toggleZenMode()
                        val msg = if (isZen) "Zen Lyrics Mode [z: restore]" else "Split View Restored"
                        player.setStatus(msg)
                    }
                    'v'.code -> { // Cycle layout modes (Split / Zen / Full Queue)
                        val mode = renderer.cycleLayout()
                        player.setStatus("🪟 Layout: ${mode.label}")
                    }
                    't'.code -> { // Cycle color themes
                        val newTheme = renderer.cycleTheme()
                        player.setStatus("🎨 Theme: ${newTheme.name}")
                    }
                    '/'.code -> {
                        renderer.isSearchingInput = true
                        renderer.searchQuery = ""
                        renderer.currentView = TuiView.SEARCH
                        if (renderer.layoutMode == TuiLayout.ZEN) {
                            renderer.layoutMode = TuiLayout.SPLIT
                        }
                    }
                    ' '.code -> {
                        player.togglePlayPause()
                    }
                    's'.code -> { // Toggle shuffle
                        val shuf = player.toggleShuffle()
                        player.setStatus(if (shuf) "🔀 Shuffle: ON" else "🔀 Shuffle: OFF")
                    }
                    'r'.code -> { // Cycle repeat mode
                        val rep = player.cycleRepeatMode()
                        player.setStatus("🔁 Repeat: ${rep.label}")
                    }
                    'm'.code -> { // Toggle mute
                        val muted = player.toggleMute()
                        player.setStatus(if (muted) "🔇 Audio MUTED" else "🔊 Audio UNMUTED (${(player.volume.value * 100).toInt()}%)")
                    }
                    '['.code -> { // Lyrics offset -500ms
                        val off = lyrics.adjustOffset(-500)
                        player.setStatus("Lyrics offset: ${if (off >= 0) "+${off}ms" else "${off}ms"}")
                    }
                    ']'.code -> { // Lyrics offset +500ms
                        val off = lyrics.adjustOffset(500)
                        player.setStatus("Lyrics offset: ${if (off >= 0) "+${off}ms" else "${off}ms"}")
                    }
                    'H'.code -> { // Fast seek back 15s
                        player.seekBy(-15.0)
                        player.setStatus("Seek -15s")
                    }
                    'L'.code -> { // Fast seek forward 15s
                        player.seekBy(15.0)
                        player.setStatus("Seek +15s")
                    }
                    9 -> { // Tab (cycle Search -> Queue -> Favorites)
                        if (renderer.layoutMode == TuiLayout.ZEN) {
                            renderer.layoutMode = TuiLayout.SPLIT
                        }
                        renderer.currentView = when (renderer.currentView) {
                            TuiView.SEARCH -> TuiView.QUEUE
                            TuiView.QUEUE -> TuiView.FAVORITES
                            TuiView.FAVORITES -> TuiView.SEARCH
                        }
                    }
                    '1'.code -> {
                        if (renderer.layoutMode == TuiLayout.ZEN) {
                            renderer.layoutMode = TuiLayout.SPLIT
                        }
                        renderer.currentView = TuiView.SEARCH
                    }
                    '2'.code -> {
                        if (renderer.layoutMode == TuiLayout.ZEN) {
                            renderer.layoutMode = TuiLayout.SPLIT
                        }
                        renderer.currentView = TuiView.QUEUE
                    }
                    '3'.code -> {
                        if (renderer.layoutMode == TuiLayout.ZEN) {
                            renderer.layoutMode = TuiLayout.SPLIT
                        }
                        renderer.currentView = TuiView.FAVORITES
                    }
                    'j'.code -> {
                        when (renderer.currentView) {
                            TuiView.SEARCH -> {
                                if (renderer.searchResults.isNotEmpty()) {
                                    renderer.selectedSearchIndex =
                                        (renderer.selectedSearchIndex + 1).coerceAtMost(renderer.searchResults.size - 1)
                                }
                            }
                            TuiView.QUEUE -> {
                                if (player.queue.isNotEmpty()) {
                                    renderer.selectedQueueIndex =
                                        (renderer.selectedQueueIndex + 1).coerceAtMost(player.queue.size - 1)
                                }
                            }
                            TuiView.FAVORITES -> {
                                val count = CliFavorites.count()
                                if (count > 0) {
                                    renderer.selectedFavoritesIndex =
                                        (renderer.selectedFavoritesIndex + 1).coerceAtMost(count - 1)
                                }
                            }
                        }
                    }
                    'k'.code -> {
                        when (renderer.currentView) {
                            TuiView.SEARCH -> {
                                if (renderer.searchResults.isNotEmpty()) {
                                    renderer.selectedSearchIndex =
                                        (renderer.selectedSearchIndex - 1).coerceAtLeast(0)
                                }
                            }
                            TuiView.QUEUE -> {
                                if (player.queue.isNotEmpty()) {
                                    renderer.selectedQueueIndex =
                                        (renderer.selectedQueueIndex - 1).coerceAtLeast(0)
                                }
                            }
                            TuiView.FAVORITES -> {
                                val count = CliFavorites.count()
                                if (count > 0) {
                                    renderer.selectedFavoritesIndex =
                                        (renderer.selectedFavoritesIndex - 1).coerceAtLeast(0)
                                }
                            }
                        }
                    }
                    10, 13 -> { // Enter
                        when (renderer.currentView) {
                            TuiView.SEARCH -> {
                                val selected = renderer.searchResults.getOrNull(renderer.selectedSearchIndex)
                                if (selected != null) {
                                    player.playQueue(renderer.searchResults, renderer.selectedSearchIndex)
                                }
                            }
                            TuiView.QUEUE -> {
                                player.playIndex(renderer.selectedQueueIndex)
                            }
                            TuiView.FAVORITES -> {
                                val favs = CliFavorites.getFavorites()
                                if (favs.isNotEmpty()) {
                                    val idx = renderer.selectedFavoritesIndex.coerceIn(0, favs.size - 1)
                                    player.playQueue(favs, idx)
                                    player.setStatus("Playing Favorites (${favs.size} songs)")
                                }
                            }
                        }
                    }
                    'a'.code -> { // Add to queue
                        when (renderer.currentView) {
                            TuiView.SEARCH -> {
                                val selected = renderer.searchResults.getOrNull(renderer.selectedSearchIndex)
                                if (selected != null) {
                                    player.addToQueue(selected)
                                    player.setStatus("Added to queue: ${selected.title}")
                                }
                            }
                            TuiView.FAVORITES -> {
                                val favs = CliFavorites.getFavorites()
                                val selected = favs.getOrNull(renderer.selectedFavoritesIndex)
                                if (selected != null) {
                                    player.addToQueue(selected)
                                    player.setStatus("Added to queue: ${selected.title}")
                                }
                            }
                            else -> {}
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
                        player.setStatus("Cleared queue")
                    }
                    27 -> { // Escape sequence (e.g. arrow keys)
                        val next1 = reader.read(10)
                        if (next1 == '['.code) {
                            val next2 = reader.read(10)
                            when (next2) {
                                'A'.code -> { // Up arrow
                                    when (renderer.currentView) {
                                        TuiView.SEARCH -> renderer.selectedSearchIndex = (renderer.selectedSearchIndex - 1).coerceAtLeast(0)
                                        TuiView.QUEUE -> renderer.selectedQueueIndex = (renderer.selectedQueueIndex - 1).coerceAtLeast(0)
                                        TuiView.FAVORITES -> {
                                            val count = CliFavorites.count()
                                            if (count > 0) renderer.selectedFavoritesIndex = (renderer.selectedFavoritesIndex - 1).coerceAtLeast(0)
                                        }
                                    }
                                }
                                'B'.code -> { // Down arrow
                                    when (renderer.currentView) {
                                        TuiView.SEARCH -> {
                                            if (renderer.searchResults.isNotEmpty()) {
                                                renderer.selectedSearchIndex = (renderer.selectedSearchIndex + 1).coerceAtMost(renderer.searchResults.size - 1)
                                            }
                                        }
                                        TuiView.QUEUE -> {
                                            if (player.queue.isNotEmpty()) {
                                                renderer.selectedQueueIndex = (renderer.selectedQueueIndex + 1).coerceAtMost(player.queue.size - 1)
                                            }
                                        }
                                        TuiView.FAVORITES -> {
                                            val count = CliFavorites.count()
                                            if (count > 0) {
                                                renderer.selectedFavoritesIndex = (renderer.selectedFavoritesIndex + 1).coerceAtMost(count - 1)
                                            }
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
