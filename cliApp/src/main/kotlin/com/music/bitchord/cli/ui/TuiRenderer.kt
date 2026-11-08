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

object AnsiUtils {
    private val ANSI_REGEX = "\u001B\\[[;\\d]*[a-zA-Z]".toRegex()

    fun stripAnsi(s: String): String = s.replace(ANSI_REGEX, "")

    fun visualWidth(s: String): Int {
        val clean = stripAnsi(s)
        var w = 0
        var i = 0
        while (i < clean.length) {
            val cp = clean.codePointAt(i)
            w += charWidth(cp)
            i += Character.charCount(cp)
        }
        return w
    }

    private fun charWidth(cp: Int): Int {
        return when {
            cp in 0x200B..0x200F || cp in 0xFE00..0xFE0F -> 0
            cp in 0x1F000..0x1FAFF -> 2
            cp in 0x2300..0x23FF -> 2
            cp in 0x1100..0x115F || cp in 0x2E80..0x9FFF || cp in 0xAC00..0xD7AF || cp in 0xF900..0xFAFF -> 2
            cp in 0xFF01..0xFF60 || cp in 0xFFE0..0xFFE6 -> 2
            else -> 1
        }
    }

    fun padToWidth(s: String, targetWidth: Int): String {
        val curW = visualWidth(s)
        return if (curW < targetWidth) {
            s + " ".repeat(targetWidth - curW)
        } else {
            s
        }
    }

    fun clipToWidth(s: String, maxWidth: Int): String {
        if (visualWidth(s) <= maxWidth) return s
        val sb = StringBuilder()
        var curW = 0
        var inEscape = false
        var i = 0
        while (i < s.length) {
            if (s[i] == '\u001B') {
                inEscape = true
                sb.append(s[i])
                i++
                continue
            }
            if (inEscape) {
                sb.append(s[i])
                if (s[i].isLetter()) inEscape = false
                i++
                continue
            }
            val cp = s.codePointAt(i)
            val w = charWidth(cp)
            if (curW + w > maxWidth) break
            curW += w
            sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        sb.append("\u001B[0m")
        return sb.toString()
    }

    fun fitToWidth(s: String, width: Int): String {
        return padToWidth(clipToWidth(s, width), width)
    }
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
        val size = terminal.size
        val width = if (size.columns > 0) size.columns else 80
        val height = if (size.rows > 0) size.rows else 24

        val rows = ArrayList<String>(height)

        // 1. Header (2 rows: title + tabs, horizontal border)
        rows.add(buildHeader(width))
        rows.add("\u001B[90m" + "─".repeat((width - 2).coerceAtLeast(10)) + "\u001B[0m")

        // 2. Body (height - 6 rows)
        val bodyHeight = (height - 6).coerceAtLeast(4)
        val leftWidth = ((width - 3) / 2).coerceAtLeast(10)
        val rightWidth = (width - 2 - leftWidth - 1).coerceAtLeast(10)

        val leftLines = renderLeftPane(leftWidth, bodyHeight)
        val rightLines = renderRightPane(rightWidth, bodyHeight)

        for (row in 0 until bodyHeight) {
            val left = leftLines.getOrElse(row) { "".padEnd(leftWidth) }
            val right = rightLines.getOrElse(row) { "".padEnd(rightWidth) }
            rows.add("$left \u001B[90m│\u001B[0m $right")
        }

        // 3. Footer (4 rows: separator, track info, progress bar, keybindings)
        rows.add("\u001B[90m" + "─".repeat((width - 2).coerceAtLeast(10)) + "\u001B[0m")
        rows.add(buildTrackInfo(width))
        rows.add(buildProgressBar(width))
        rows.add(buildKeybinds(width))

        // Position line-by-line using absolute cursor coordinates with NO newline \n
        val sb = StringBuilder()
        sb.append("\u001B[?25l") // Ensure cursor remains hidden
        for (i in 0 until height) {
            sb.append("\u001B[").append(i + 1).append(";1H")
            if (i < rows.size) {
                sb.append(rows[i])
            }
            sb.append("\u001B[K") // Clear to end of line
        }

        writer.print(sb.toString())
        writer.flush()
    }

    private fun buildHeader(width: Int): String {
        val title = if (width >= 96) {
            " \u001B[1;36m🎵 BITCHORD TUI\u001B[0m \u001B[90m[v1.9.1 - Ultra-Low-Resource Linux Edition]\u001B[0m"
        } else if (width >= 80) {
            " \u001B[1;36m🎵 BITCHORD TUI\u001B[0m \u001B[90m[v1.9.1]\u001B[0m"
        } else {
            " \u001B[1;36m🎵 BITCHORD\u001B[0m"
        }

        val queueCount = player.queue.size
        val viewTabs = if (currentView == TuiView.SEARCH) {
            "\u001B[1;7;32m [1] SEARCH \u001B[0m \u001B[90m [2] QUEUE ($queueCount) \u001B[0m"
        } else {
            "\u001B[90m [1] SEARCH \u001B[0m \u001B[1;7;32m [2] QUEUE ($queueCount) \u001B[0m"
        }

        val titleW = AnsiUtils.visualWidth(title)
        val tabsW = AnsiUtils.visualWidth(viewTabs)
        val spacing = (width - 1 - titleW - tabsW).coerceAtLeast(1)

        val combined = title + " ".repeat(spacing) + viewTabs
        return AnsiUtils.fitToWidth(combined, width - 1)
    }

    private fun renderLeftPane(width: Int, height: Int): List<String> {
        val lines = mutableListOf<String>()

        if (currentView == TuiView.SEARCH) {
            val promptPrefix = if (isSearchingInput) {
                "\u001B[1;33mSearch: \u001B[0;37m$searchQuery\u001B[7m \u001B[0m"
            } else if (isSearchingNetwork) {
                "\u001B[1;36mSearching YouTube Music for \"$searchQuery\"...\u001B[0m"
            } else if (searchQuery.isNotBlank()) {
                "\u001B[90mSearch (press / to edit): \u001B[37m$searchQuery\u001B[0m"
            } else {
                "\u001B[90mPress / to search songs on YouTube Music\u001B[0m"
            }
            lines.add(AnsiUtils.fitToWidth(promptPrefix, width))
            lines.add("\u001B[90m" + "┄".repeat((width - 1).coerceAtLeast(5)) + "\u001B[0m")

            val listRows = height - 2
            if (searchResults.isEmpty()) {
                lines.add(AnsiUtils.fitToWidth("  \u001B[90mNo search results. Press / to search.\u001B[0m", width))
                while (lines.size < height) lines.add("".padEnd(width))
                return lines
            }

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
                val clipped = fullTitle.take(availableForTitle)

                val line = if (isSelected) {
                    "\u001B[1;7;36m > $clipped$durPadding \u001B[0m"
                } else {
                    "   \u001B[37m$clipped\u001B[90m$durPadding\u001B[0m"
                }
                lines.add(AnsiUtils.fitToWidth(line, width))
            }
        } else {
            lines.add(AnsiUtils.fitToWidth(" \u001B[1;36mPlayback Queue (${player.queue.size} songs)\u001B[0m", width))
            lines.add("\u001B[90m" + "┄".repeat((width - 1).coerceAtLeast(5)) + "\u001B[0m")

            val listRows = height - 2
            val queue = player.queue
            if (queue.isEmpty()) {
                lines.add(AnsiUtils.fitToWidth("  \u001B[90mQueue is empty. Search songs and press Enter or 'a' to add.\u001B[0m", width))
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
                val clipped = fullTitle.take(availableForTitle)

                val line = if (isSelected) {
                    "\u001B[1;7;32m$prefix $clipped$durPadding \u001B[0m"
                } else if (isCurrent) {
                    "$prefix \u001B[1;32m$clipped\u001B[90m$durPadding\u001B[0m"
                } else {
                    "$prefix \u001B[37m$clipped\u001B[90m$durPadding\u001B[0m"
                }
                lines.add(AnsiUtils.fitToWidth(line, width))
            }
        }

        while (lines.size < height) lines.add("".padEnd(width))
        return lines
    }

    private fun renderRightPane(width: Int, height: Int): List<String> {
        val lines = mutableListOf<String>()
        val track = player.currentTrack.value

        lines.add(AnsiUtils.fitToWidth(" \u001B[1;35mSynced Lyrics\u001B[0m" + if (lyrics.isLoading.value) " \u001B[90m(loading...)\u001B[0m" else "", width))
        lines.add("\u001B[90m" + "┄".repeat((width - 1).coerceAtLeast(5)) + "\u001B[0m")

        val lyricRows = height - 2
        val allLyrics = lyrics.lines.value
        val curIdx = lyrics.currentIndex.value

        if (track == null) {
            lines.add(AnsiUtils.fitToWidth("  \u001B[90mNo track playing.\u001B[0m", width))
            while (lines.size < height) lines.add("".padEnd(width))
            return lines
        }

        if (allLyrics.isEmpty()) {
            val msg = if (lyrics.isLoading.value) {
                "  \u001B[33mFetching synced lyrics from LRCLIB & databases...\u001B[0m"
            } else {
                "  \u001B[90mNo synced lyrics found for this track.\u001B[0m"
            }
            lines.add(AnsiUtils.fitToWidth(msg, width))
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
            val clippedText = lyric.text.take(width - 5)

            val line = if (isActive) {
                " \u001B[1;36m▶ \u001B[1;7;36m $clippedText \u001B[0m"
            } else if (i < curIdx) {
                "   \u001B[90m$clippedText\u001B[0m"
            } else {
                "   \u001B[37m$clippedText\u001B[0m"
            }
            lines.add(AnsiUtils.fitToWidth(line, width))
        }

        while (lines.size < height) lines.add("".padEnd(width))
        return lines
    }

    private fun buildTrackInfo(width: Int): String {
        val track = player.currentTrack.value
        val isPlaying = player.isPlaying.value
        val isBuffering = player.isBuffering.value

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
        return AnsiUtils.fitToWidth(" $stateIcon  $trackInfo$status", width - 1)
    }

    private fun buildProgressBar(width: Int): String {
        val posMs = player.positionMs.value
        val durMs = player.durationMs.value
        val volPct = (player.volume.value * 100).toInt()

        val posSec = posMs / 1000
        val durSec = durMs / 1000
        val posStr = formatTime(posSec)
        val durStr = formatTime(durSec)

        val barAvailable = (width - 26).coerceAtLeast(10)
        val progress = if (durSec > 0) (posSec.toDouble() / durSec).coerceIn(0.0, 1.0) else 0.0
        val filled = (progress * barAvailable).toInt()
        val empty = (barAvailable - filled).coerceAtLeast(0)
        val bar = "\u001B[32m" + "━".repeat(filled) + "\u001B[90m" + "─".repeat(empty) + "\u001B[0m"

        return AnsiUtils.fitToWidth(" $posStr [$bar] $durStr  \u001B[90mVol: \u001B[37m$volPct%\u001B[0m", width - 1)
    }

    private fun buildKeybinds(width: Int): String {
        val full = "\u001B[90m[Space]\u001B[37mPlay/Pause \u001B[90m[j/k]\u001B[37mNav \u001B[90m[Enter]\u001B[37mPlay \u001B[90m[a]\u001B[37mQueue \u001B[90m[n/p]\u001B[37mNext/Prev \u001B[90m[h/l]\u001B[37mSeek \u001B[90m[/]\u001B[37mSearch \u001B[90m[Tab]\u001B[37mView \u001B[90m[+/-]\u001B[37mVol \u001B[90m[q]\u001B[37mQuit\u001B[0m"
        val compact = "\u001B[90m[Space]\u001B[37mPlay \u001B[90m[j/k]\u001B[37mNav \u001B[90m[Enter]\u001B[37mSelect \u001B[90m[a]\u001B[37mAdd \u001B[90m[/]\u001B[37mSearch \u001B[90m[Tab]\u001B[37mView \u001B[90m[+/-]\u001B[37mVol \u001B[90m[q]\u001B[37mQuit\u001B[0m"
        val minimal = "\u001B[90m[Space]\u001B[37mPlay \u001B[90m[j/k]\u001B[37mNav \u001B[90m[/]\u001B[37mSearch \u001B[90m[Tab]\u001B[37mTab \u001B[90m[q]\u001B[37mQuit\u001B[0m"

        val binds = when {
            width >= 105 -> full
            width >= 85 -> compact
            else -> minimal
        }
        return AnsiUtils.fitToWidth(" $binds", width - 1)
    }

    private fun formatTime(sec: Long): String {
        val m = sec / 60
        val s = sec % 60
        return "%d:%02d".format(m, s)
    }
}
