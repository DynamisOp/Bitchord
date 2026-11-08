package com.music.bitchord.cli.ui

import com.music.bitchord.cli.lyrics.CliLyrics
import com.music.bitchord.cli.player.CliPlayer
import com.music.bitchord.data.model.Song
import org.jline.terminal.Terminal
import java.io.PrintWriter

enum class TuiView {
    SEARCH,
    QUEUE,
}

class TuiRenderer(
    private val terminal: Terminal,
    private val player: CliPlayer,
    private val lyrics: CliLyrics,
) {
    private val writer: PrintWriter = terminal.writer()

    var currentView: TuiView = TuiView.SEARCH
    var searchResults: List<Song> = emptyList()
    var selectedSearchIndex: Int = 0
    var selectedQueueIndex: Int = 0
    var searchQuery: String = ""
    var isSearchingInput: Boolean = false
    var isSearchingNetwork: Boolean = false

    fun render() {
        val width = terminal.width.coerceAtLeast(80)
        val height = terminal.height.coerceAtLeast(24)

        val sb = StringBuilder()
        // Cursor to top-left
        sb.append("\u001B[H")

        // 1. Header (2 lines)
        renderHeader(sb, width)

        // 2. Body (height - 6 lines)
        val bodyHeight = (height - 6).coerceAtLeast(10)
        val leftWidth = (width / 2) - 1
        val rightWidth = width - leftWidth - 1

        val leftLines = renderLeftPane(leftWidth, bodyHeight)
        val rightLines = renderRightPane(rightWidth, bodyHeight)

        for (row in 0 until bodyHeight) {
            val left = leftLines.getOrElse(row) { "".padEnd(leftWidth) }
            val right = rightLines.getOrElse(row) { "".padEnd(rightWidth) }
            sb.append(left)
            sb.append("\u001B[90m│\u001B[0m")
            sb.append(right)
            sb.append("\u001B[K\n")
        }

        // 3. Footer / Player HUD (4 lines)
        renderFooter(sb, width)

        writer.print(sb.toString())
        writer.flush()
    }

    private fun renderHeader(sb: StringBuilder, width: Int) {
        val title = " \u001B[1;36m🎵 BITCHORD TUI\u001B[0m \u001B[90m[v1.9.1 - Ultra-Low Resource Linux Edition]\u001B[0m"
        val viewTabs = if (currentView == TuiView.SEARCH) {
            "\u001B[1;7;32m [1] SEARCH \u001B[0m \u001B[90m [2] QUEUE (${player.queue.size}) \u001B[0m"
        } else {
            "\u001B[90m [1] SEARCH \u001B[0m \u001B[1;7;32m [2] QUEUE (${player.queue.size}) \u001B[0m"
        }
        sb.append(title)
        val plainLen = 15 + 44
        val tabLen = 28
        val spacing = (width - plainLen - tabLen).coerceAtLeast(1)
        sb.append(" ".repeat(spacing))
        sb.append(viewTabs)
        sb.append("\u001B[K\n")

        sb.append("\u001B[90m" + "─".repeat(width) + "\u001B[0m\u001B[K\n")
    }

    private fun renderLeftPane(width: Int, height: Int): List<String> {
        val lines = mutableListOf<String>()

        if (currentView == TuiView.SEARCH) {
            // Search Input Header
            val promptPrefix = if (isSearchingInput) {
                "\u001B[1;33mSearch: \u001B[0;37m$searchQuery\u001B[7m \u001B[0m"
            } else if (isSearchingNetwork) {
                "\u001B[1;36mSearching YouTube Music for \"$searchQuery\"...\u001B[0m"
            } else if (searchQuery.isNotBlank()) {
                "\u001B[90mSearch (press / to edit): \u001B[37m$searchQuery\u001B[0m"
            } else {
                "\u001B[90mPress / to search songs on YouTube Music\u001B[0m"
            }
            lines.add(padAnsi(promptPrefix, width))
            lines.add("\u001B[90m" + "┄".repeat(width) + "\u001B[0m")

            val listRows = height - 2
            if (searchResults.isEmpty()) {
                lines.add(padAnsi("  \u001B[90mNo search results. Press / to search.\u001B[0m", width))
                while (lines.size < height) lines.add("".padEnd(width))
                return lines
            }

            // Scroll window
            val selected = selectedSearchIndex.coerceIn(0, searchResults.size - 1)
            val startIdx = (selected - listRows / 2).coerceAtLeast(0)
                .coerceAtMost((searchResults.size - listRows).coerceAtLeast(0))

            for (i in startIdx until minOf(searchResults.size, startIdx + listRows)) {
                val song = searchResults[i]
                val isSelected = (i == selected)
                val duration = song.durationText ?: ""
                val durPadding = if (duration.isNotBlank()) " [$duration]" else ""
                val availableForTitle = (width - duration.length - 8).coerceAtLeast(10)
                val fullTitle = "${song.title} - ${song.artist}"
                val clipped = fullTitle.take(availableForTitle).padEnd(availableForTitle)

                val line = if (isSelected) {
                    "\u001B[1;7;36m > $clipped$durPadding \u001B[0m"
                } else {
                    "   \u001B[37m$clipped\u001B[90m$durPadding\u001B[0m"
                }
                lines.add(padAnsi(line, width))
            }
        } else {
            // Queue View
            lines.add(padAnsi(" \u001B[1;36mPlayback Queue (${player.queue.size} songs)\u001B[0m", width))
            lines.add("\u001B[90m" + "┄".repeat(width) + "\u001B[0m")

            val listRows = height - 2
            val queue = player.queue
            if (queue.isEmpty()) {
                lines.add(padAnsi("  \u001B[90mQueue is empty. Search songs and press Enter or 'a' to add.\u001B[0m", width))
                while (lines.size < height) lines.add("".padEnd(width))
                return lines
            }

            val selected = selectedQueueIndex.coerceIn(0, queue.size - 1)
            val startIdx = (selected - listRows / 2).coerceAtLeast(0)
                .coerceAtMost((queue.size - listRows).coerceAtLeast(0))

            for (i in startIdx until minOf(queue.size, startIdx + listRows)) {
                val song = queue[i]
                val isCurrent = (i == player.queueIndex)
                val isSelected = (i == selected)
                val prefix = when {
                    isCurrent && isSelected -> "\u001B[1;32m▶>\u001B[0m"
                    isCurrent -> "\u001B[1;32m ▶\u001B[0m"
                    isSelected -> " >"
                    else -> "  "
                }
                val duration = song.durationText ?: ""
                val durPadding = if (duration.isNotBlank()) " [$duration]" else ""
                val availableForTitle = (width - duration.length - 8).coerceAtLeast(10)
                val fullTitle = "${i + 1}. ${song.title} - ${song.artist}"
                val clipped = fullTitle.take(availableForTitle).padEnd(availableForTitle)

                val line = if (isSelected) {
                    "\u001B[1;7;32m$prefix $clipped$durPadding \u001B[0m"
                } else if (isCurrent) {
                    "$prefix \u001B[1;32m$clipped\u001B[90m$durPadding\u001B[0m"
                } else {
                    "$prefix \u001B[37m$clipped\u001B[90m$durPadding\u001B[0m"
                }
                lines.add(padAnsi(line, width))
            }
        }

        while (lines.size < height) lines.add("".padEnd(width))
        return lines
    }

    private fun renderRightPane(width: Int, height: Int): List<String> {
        val lines = mutableListOf<String>()
        val track = player.currentTrack.value

        lines.add(padAnsi(" \u001B[1;35mSynced Lyrics\u001B[0m" + if (lyrics.isLoading.value) " \u001B[90m(loading...)\u001B[0m" else "", width))
        lines.add("\u001B[90m" + "┄".repeat(width) + "\u001B[0m")

        val lyricRows = height - 2
        val allLyrics = lyrics.lines.value
        val curIdx = lyrics.currentIndex.value

        if (track == null) {
            lines.add(padAnsi("  \u001B[90mNo track playing.\u001B[0m", width))
            while (lines.size < height) lines.add("".padEnd(width))
            return lines
        }

        if (allLyrics.isEmpty()) {
            val msg = if (lyrics.isLoading.value) {
                "  \u001B[33mFetching synced lyrics from LRCLIB & databases...\u001B[0m"
            } else {
                "  \u001B[90mNo synced lyrics found for this track.\u001B[0m"
            }
            lines.add(padAnsi(msg, width))
            while (lines.size < height) lines.add("".padEnd(width))
            return lines
        }

        // Center current lyric line in the pane
        val activeLine = curIdx.coerceIn(0, allLyrics.size - 1)
        val half = lyricRows / 2
        val startIdx = (activeLine - half).coerceAtLeast(0)
            .coerceAtMost((allLyrics.size - lyricRows).coerceAtLeast(0))

        for (i in startIdx until minOf(allLyrics.size, startIdx + lyricRows)) {
            val lyric = allLyrics[i]
            val isActive = (i == curIdx)
            val clippedText = lyric.text.take(width - 4)

            val line = if (isActive) {
                " \u001B[1;36m▶ \u001B[1;7;36m $clippedText \u001B[0m"
            } else if (i < curIdx) {
                "   \u001B[90m$clippedText\u001B[0m"
            } else {
                "   \u001B[37m$clippedText\u001B[0m"
            }
            lines.add(padAnsi(line, width))
        }

        while (lines.size < height) lines.add("".padEnd(width))
        return lines
    }

    private fun renderFooter(sb: StringBuilder, width: Int) {
        sb.append("\u001B[90m" + "─".repeat(width) + "\u001B[0m\u001B[K\n")

        val track = player.currentTrack.value
        val isPlaying = player.isPlaying.value
        val isBuffering = player.isBuffering.value
        val posMs = player.positionMs.value
        val durMs = player.durationMs.value
        val volPct = (player.volume.value * 100).toInt()

        val stateIcon = when {
            isBuffering -> "\u001B[1;33m⏳ BUFFERING\u001B[0m"
            isPlaying -> "\u001B[1;32m▶ PLAYING\u001B[0m"
            track != null -> "\u001B[1;33m⏸ PAUSED\u001B[0m"
            else -> "\u001B[90m⏹ STOPPED\u001B[0m"
        }

        val trackInfo = if (track != null) {
            "\u001B[1;37m${track.title}\u001B[0m \u001B[90mby\u001B[0m \u001B[36m${track.artist}\u001B[0m"
        } else {
            "\u001B[90mNothing playing\u001B[0m"
        }

        val status = player.statusMessage.value?.let { " \u001B[33m[$it]\u001B[0m" } ?: ""

        sb.append(" $stateIcon  $trackInfo$status\u001B[K\n")

        // Progress bar line
        val posSec = posMs / 1000
        val durSec = durMs / 1000
        val posStr = formatTime(posSec)
        val durStr = formatTime(durSec)

        val barAvailable = (width - 24).coerceAtLeast(10)
        val progress = if (durSec > 0) (posSec.toDouble() / durSec).coerceIn(0.0, 1.0) else 0.0
        val filled = (progress * barAvailable).toInt()
        val empty = (barAvailable - filled).coerceAtLeast(0)
        val bar = "\u001B[32m" + "━".repeat(filled) + "\u001B[90m" + "─".repeat(empty) + "\u001B[0m"

        sb.append(" $posStr [$bar] $durStr  \u001B[90mVol: \u001B[37m$volPct%\u001B[0m\u001B[K\n")

        // Keybinds bar
        val keybinds = "\u001B[90m[Space]\u001B[37mPlay/Pause \u001B[90m[j/k]\u001B[37mNav \u001B[90m[Enter]\u001B[37mPlay \u001B[90m[a]\u001B[37mQueue \u001B[90m[n/p]\u001B[37mNext/Prev \u001B[90m[h/l]\u001B[37mSeek \u001B[90m[/]\u001B[37mSearch \u001B[90m[Tab]\u001B[37mView \u001B[90m[+/-]\u001B[37mVol \u001B[90m[q]\u001B[37mQuit\u001B[0m"
        sb.append(padAnsi(" " + keybinds, width))
    }

    private fun padAnsi(str: String, width: Int): String {
        val visibleLen = stripAnsi(str).length
        val pad = (width - visibleLen).coerceAtLeast(0)
        return str + " ".repeat(pad)
    }

    private fun stripAnsi(s: String): String =
        s.replace(ANSI_REGEX, "")

    private fun formatTime(sec: Long): String {
        val m = sec / 60
        val s = sec % 60
        return "%d:%02d".format(m, s)
    }

    companion object {
        private val ANSI_REGEX = "\u001B\\[[;\\d]*m".toRegex()
    }
}
