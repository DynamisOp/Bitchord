package com.music.bitchord.cli.ui

import com.music.bitchord.cli.CliConfig
import com.music.bitchord.cli.lyrics.CliLyrics
import com.music.bitchord.cli.player.CliPlayer
import com.music.bitchord.cli.player.CliRepeatMode
import com.music.bitchord.data.model.Song
import org.jline.terminal.Terminal
import java.io.PrintWriter

enum class TuiView {
    SEARCH,
    QUEUE,
}

enum class TuiLayout(val label: String) {
    SPLIT("Split View (50/50)"),
    ZEN("Queue Minimized (Zen Lyrics)"),
    QUEUE_ONLY("Full Queue View"),
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
        sb.append(CliTheme.RESET)
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
    var layoutMode: TuiLayout = CliConfig.layoutMode
        set(value) {
            field = value
            CliConfig.layoutMode = value
        }

    var theme: CliTheme = CliTheme.find(CliConfig.themeId)
        set(value) {
            field = value
            CliConfig.themeId = value.id
        }

    var searchResults: List<Song> = emptyList()
    var selectedSearchIndex: Int = 0
    var selectedQueueIndex: Int = 0
    var searchQuery: String = ""
    var isSearchingInput: Boolean = false
    var isSearchingNetwork: Boolean = false

    fun toggleZenMode(): Boolean {
        layoutMode = if (layoutMode == TuiLayout.ZEN) TuiLayout.SPLIT else TuiLayout.ZEN
        return layoutMode == TuiLayout.ZEN
    }

    fun cycleLayout(): TuiLayout {
        val modes = TuiLayout.values()
        val nextIdx = (modes.indexOf(layoutMode) + 1) % modes.size
        layoutMode = modes[nextIdx]
        return layoutMode
    }

    fun cycleTheme(): CliTheme {
        val themes = CliTheme.ALL
        val nextIdx = (themes.indexOf(theme) + 1) % themes.size
        theme = themes[nextIdx]
        return theme
    }

    fun render() {
        val size = terminal.size
        val width = if (size.columns > 0) size.columns else 80
        val height = if (size.rows > 0) size.rows else 24

        val rows = ArrayList<String>(height)

        // 1. Header (2 rows: title + tabs, horizontal border)
        rows.add(buildHeader(width))
        rows.add(theme.border + "─".repeat((width - 2).coerceAtLeast(10)) + CliTheme.RESET)

        // 2. Body (height - 6 rows)
        val bodyHeight = (height - 6).coerceAtLeast(4)

        when (layoutMode) {
            TuiLayout.ZEN -> {
                // Queue is minimized: lyrics occupy full screen width
                val fullWidth = (width - 2).coerceAtLeast(10)
                val rightLines = renderRightPane(fullWidth, bodyHeight, isZen = true)
                for (row in 0 until bodyHeight) {
                    val line = rightLines.getOrElse(row) { "".padEnd(fullWidth) }
                    rows.add(" $line")
                }
            }
            TuiLayout.QUEUE_ONLY -> {
                // Full-width Queue or Search list
                val fullWidth = (width - 2).coerceAtLeast(10)
                val leftLines = renderLeftPane(fullWidth, bodyHeight)
                for (row in 0 until bodyHeight) {
                    val line = leftLines.getOrElse(row) { "".padEnd(fullWidth) }
                    rows.add(" $line")
                }
            }
            TuiLayout.SPLIT -> {
                // Default 50/50 split pane
                val leftWidth = ((width - 3) / 2).coerceAtLeast(10)
                val rightWidth = (width - 2 - leftWidth - 1).coerceAtLeast(10)

                val leftLines = renderLeftPane(leftWidth, bodyHeight)
                val rightLines = renderRightPane(rightWidth, bodyHeight, isZen = false)

                for (row in 0 until bodyHeight) {
                    val left = leftLines.getOrElse(row) { "".padEnd(leftWidth) }
                    val right = rightLines.getOrElse(row) { "".padEnd(rightWidth) }
                    rows.add("$left ${theme.border}│${CliTheme.RESET} $right")
                }
            }
        }

        // 3. Footer (4 rows: separator, track info + visualizer, progress bar, keybindings)
        rows.add(theme.border + "─".repeat((width - 2).coerceAtLeast(10)) + CliTheme.RESET)
        rows.add(buildTrackInfo(width))
        rows.add(buildProgressBar(width))
        rows.add(buildKeybinds(width))

        // Position line-by-line using absolute cursor coordinates with zero newline \n
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
        val shortTheme = theme.name.substringBefore(" (")
        val title = if (width >= 105) {
            " ${theme.primary}🎵 BITCHORD TUI${CliTheme.RESET} ${theme.textMuted}[v1.9.1 · $shortTheme]${CliTheme.RESET}"
        } else if (width >= 80) {
            " ${theme.primary}🎵 BITCHORD TUI${CliTheme.RESET} ${theme.textMuted}[$shortTheme]${CliTheme.RESET}"
        } else {
            " ${theme.primary}🎵 BITCHORD${CliTheme.RESET}"
        }

        val queueCount = player.queue.size
        val shufBadge = if (player.isShuffle.value) " ${theme.secondary}[🔀 SHUF]${CliTheme.RESET}" else ""
        val repBadge = when (player.repeatMode.value) {
            CliRepeatMode.ALL -> " ${theme.primary}[🔁 ALL]${CliTheme.RESET}"
            CliRepeatMode.ONE -> " ${theme.warning}[🔂 ONE]${CliTheme.RESET}"
            CliRepeatMode.OFF -> ""
        }

        val viewTabs = when (layoutMode) {
            TuiLayout.ZEN -> {
                "${theme.textMuted}[1] SEARCH  [2] QUEUE ($queueCount) ${CliTheme.RESET}${theme.primary}[z: EXPAND QUEUE]${CliTheme.RESET}$shufBadge$repBadge"
            }
            TuiLayout.QUEUE_ONLY -> {
                if (currentView == TuiView.SEARCH) {
                    "${theme.highlightBg} [1] SEARCH ${CliTheme.RESET} ${theme.textMuted} [2] QUEUE ($queueCount)$shufBadge$repBadge ${CliTheme.RESET}${theme.secondary}[v: SPLIT]${CliTheme.RESET}"
                } else {
                    "${theme.textMuted} [1] SEARCH ${CliTheme.RESET} ${theme.highlightBg} [2] QUEUE ($queueCount)$shufBadge$repBadge ${CliTheme.RESET}${theme.secondary}[v: SPLIT]${CliTheme.RESET}"
                }
            }
            TuiLayout.SPLIT -> {
                if (currentView == TuiView.SEARCH) {
                    "${theme.highlightBg} [1] SEARCH ${CliTheme.RESET} ${theme.textMuted} [2] QUEUE ($queueCount)$shufBadge$repBadge ${CliTheme.RESET}"
                } else {
                    "${theme.textMuted} [1] SEARCH ${CliTheme.RESET} ${theme.highlightBg} [2] QUEUE ($queueCount)$shufBadge$repBadge ${CliTheme.RESET}"
                }
            }
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
                "${theme.warning}Search/URL: ${theme.text}$searchQuery\u001B[7m ${CliTheme.RESET}"
            } else if (isSearchingNetwork) {
                "${theme.primary}Fetching from YouTube Music: \"$searchQuery\"...${CliTheme.RESET}"
            } else if (searchQuery.isNotBlank()) {
                "${theme.textMuted}Search (press / to edit or paste URL): ${theme.text}$searchQuery${CliTheme.RESET}"
            } else {
                "${theme.textMuted}Press / to search songs, playlists (p:), or paste YouTube link${CliTheme.RESET}"
            }
            lines.add(AnsiUtils.fitToWidth(promptPrefix, width))
            lines.add(theme.border + "┄".repeat((width - 1).coerceAtLeast(5)) + CliTheme.RESET)

            val listRows = height - 2
            if (searchResults.isEmpty()) {
                lines.add(AnsiUtils.fitToWidth("  ${theme.textMuted}No search results. Press / to search or paste a playlist.${CliTheme.RESET}", width))
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
                    "${theme.highlightBg} > $clipped$durPadding ${CliTheme.RESET}"
                } else {
                    "   ${theme.text}$clipped${theme.textMuted}$durPadding${CliTheme.RESET}"
                }
                lines.add(AnsiUtils.fitToWidth(line, width))
            }
        } else {
            val qTitle = " ${theme.primary}Playback Queue (${player.queue.size} songs)${CliTheme.RESET}"
            lines.add(AnsiUtils.fitToWidth(qTitle, width))
            lines.add(theme.border + "┄".repeat((width - 1).coerceAtLeast(5)) + CliTheme.RESET)

            val listRows = height - 2
            val queue = player.queue
            if (queue.isEmpty()) {
                lines.add(AnsiUtils.fitToWidth("  ${theme.textMuted}Queue is empty. Search songs or paste playlist URL.${CliTheme.RESET}", width))
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
                    isCurrent && isSelected -> "${theme.accent}▶>${CliTheme.RESET}"
                    isCurrent -> "${theme.accent} ▶${CliTheme.RESET}"
                    isSelected -> " >"
                    else -> "  "
                }
                val duration = song.durationText ?: ""
                val durPadding = if (duration.isNotBlank()) " [$duration]" else ""
                val availableForTitle = (width - duration.length - 8).coerceAtLeast(10)
                val fullTitle = "${i + 1}. ${song.title} - ${song.artist}"
                val clipped = fullTitle.take(availableForTitle)

                val line = if (isSelected) {
                    "${theme.highlightBg}$prefix $clipped$durPadding ${CliTheme.RESET}"
                } else if (isCurrent) {
                    "$prefix ${theme.accent}$clipped${theme.textMuted}$durPadding${CliTheme.RESET}"
                } else {
                    "$prefix ${theme.text}$clipped${theme.textMuted}$durPadding${CliTheme.RESET}"
                }
                lines.add(AnsiUtils.fitToWidth(line, width))
            }
        }

        while (lines.size < height) lines.add("".padEnd(width))
        return lines
    }

    private fun renderRightPane(width: Int, height: Int, isZen: Boolean): List<String> {
        val lines = mutableListOf<String>()
        val track = player.currentTrack.value

        val syncNotice = lyrics.offsetText.value?.let { " ${theme.warning}(sync $it)${CliTheme.RESET}" } ?: ""
        val zenBadge = if (isZen) " ${theme.textMuted}[Queue Minimized · press z to restore]${CliTheme.RESET}" else ""
        val titleText = " ${theme.secondary}Synced Lyrics${CliTheme.RESET}$syncNotice$zenBadge" +
            if (lyrics.isLoading.value) " ${theme.textMuted}(loading...)${CliTheme.RESET}" else ""

        lines.add(AnsiUtils.fitToWidth(titleText, width))
        lines.add(theme.border + "┄".repeat((width - 1).coerceAtLeast(5)) + CliTheme.RESET)

        val lyricRows = height - 2
        val allLyrics = lyrics.lines.value
        val curIdx = lyrics.currentIndex.value

        if (track == null) {
            lines.add(AnsiUtils.fitToWidth("  ${theme.textMuted}No track playing.${CliTheme.RESET}", width))
            while (lines.size < height) lines.add("".padEnd(width))
            return lines
        }

        if (allLyrics.isEmpty()) {
            val msg = if (lyrics.isLoading.value) {
                "  ${theme.warning}Fetching synced lyrics from LRCLIB & databases...${CliTheme.RESET}"
            } else {
                "  ${theme.textMuted}No synced lyrics found for this track.${CliTheme.RESET}"
            }
            lines.add(AnsiUtils.fitToWidth(msg, width))
            if (isZen && player.isPlaying.value) {
                // Show a nice center visualizer if no lyrics in zen mode
                lines.add("")
                lines.add(AnsiUtils.fitToWidth("     ${theme.visualizer}♫ ${renderVisualizer(player.visualizerBands.value)}${CliTheme.RESET}", width))
            }
            while (lines.size < height) lines.add("".padEnd(width))
            return lines
        }

        // Center current lyric line in the pane
        val activeLine = curIdx.coerceIn(0, allLyrics.size - 1)
        val half = lyricRows / 2
        val startIdx = (activeLine - half).coerceAtLeast(0)
            .coerceAtMost((allLyrics.size - lyricRows).coerceAtLeast(0))

        val indent = if (isZen && width > 70) "      " else "  "

        for (i in startIdx until minOf(allLyrics.size, startIdx + lyricRows)) {
            val lyric = allLyrics[i]
            val isActive = (i == curIdx)
            val maxTextWidth = (width - indent.length - 8).coerceAtLeast(10)
            val clippedText = lyric.text.take(maxTextWidth)

            val line = if (isActive) {
                "$indent${theme.primary}▶ ${theme.highlightBg}  $clippedText  ${CliTheme.RESET}"
            } else if (i < curIdx) {
                "$indent   ${theme.textMuted}$clippedText${CliTheme.RESET}"
            } else {
                "$indent   ${theme.text}$clippedText${CliTheme.RESET}"
            }
            lines.add(AnsiUtils.fitToWidth(line, width))
        }

        while (lines.size < height) lines.add("".padEnd(width))
        return lines
    }

    private fun renderVisualizer(bands: FloatArray): String {
        val vizChars = charArrayOf(' ', ' ', '▂', '▃', '▄', '▅', '▆', '▇', '█')
        val sb = StringBuilder()
        for (b in bands) {
            val idx = (b * 8).toInt().coerceIn(0, 8)
            sb.append(vizChars[idx])
        }
        return sb.toString()
    }

    private fun buildTrackInfo(width: Int): String {
        val track = player.currentTrack.value
        val isPlaying = player.isPlaying.value
        val isBuffering = player.isBuffering.value

        val stateIcon = when {
            isBuffering -> "${theme.warning}⏳ BUFFERING${CliTheme.RESET}"
            isPlaying -> "${theme.accent}▶ PLAYING${CliTheme.RESET}"
            track != null -> "${theme.warning}⏸ PAUSED${CliTheme.RESET}"
            else -> "${theme.textMuted}⏹ STOPPED${CliTheme.RESET}"
        }

        val trackInfo = if (track != null) {
            "${theme.text}\u001B[1m${track.title}${CliTheme.RESET} ${theme.textMuted}by${CliTheme.RESET} ${theme.primary}${track.artist}${CliTheme.RESET}"
        } else {
            "${theme.textMuted}Nothing playing${CliTheme.RESET}"
        }

        val vizStr = if (isPlaying) {
            " ${theme.visualizer}♫ ${renderVisualizer(player.visualizerBands.value)}${CliTheme.RESET}"
        } else {
            ""
        }

        val codecInfo = player.streamInfo.value?.let { " ${theme.textMuted}[$it]${CliTheme.RESET}" } ?: ""
        val status = player.statusMessage.value?.let { " ${theme.warning}[$it]${CliTheme.RESET}" } ?: ""

        return AnsiUtils.fitToWidth(" $stateIcon  $trackInfo$vizStr$codecInfo$status", width - 1)
    }

    private fun buildProgressBar(width: Int): String {
        val posMs = player.positionMs.value
        val durMs = player.durationMs.value
        val volPct = (player.volume.value * 100).toInt()

        val posSec = posMs / 1000
        val durSec = durMs / 1000
        val posStr = formatTime(posSec)
        val durStr = formatTime(durSec)

        val volLabel = if (player.isMuted.value) "\u001B[1;31mMUTED${CliTheme.RESET}" else "${theme.text}$volPct%${CliTheme.RESET}"
        val shufTag = if (player.isShuffle.value) " ${theme.secondary}🔀${CliTheme.RESET}" else ""
        val repTag = when (player.repeatMode.value) {
            CliRepeatMode.ALL -> " ${theme.primary}🔁${CliTheme.RESET}"
            CliRepeatMode.ONE -> " ${theme.warning}🔂${CliTheme.RESET}"
            CliRepeatMode.OFF -> ""
        }

        val barAvailable = (width - 32).coerceAtLeast(10)
        val progress = if (durSec > 0) (posSec.toDouble() / durSec).coerceIn(0.0, 1.0) else 0.0
        val filled = (progress * barAvailable).toInt()
        val empty = (barAvailable - filled).coerceAtLeast(0)
        val bar = theme.progressBar + "━".repeat(filled) + theme.progressEmpty + "─".repeat(empty) + CliTheme.RESET

        return AnsiUtils.fitToWidth(" $posStr [$bar] $durStr$shufTag$repTag  ${theme.textMuted}Vol: $volLabel", width - 1)
    }

    private fun buildKeybinds(width: Int): String {
        val zenLabel = if (layoutMode == TuiLayout.ZEN) "Queue" else "Zen"
        val full = "${theme.textMuted}[z]${theme.text}$zenLabel ${theme.textMuted}[t]${theme.text}Theme ${theme.textMuted}[Space]${theme.text}Play ${theme.textMuted}[j/k]${theme.text}Nav ${theme.textMuted}[Enter]${theme.text}Select ${theme.textMuted}[s]${theme.text}Shuf ${theme.textMuted}[r]${theme.text}Rep ${theme.textMuted}[m]${theme.text}Mute ${theme.textMuted}[a]${theme.text}Queue ${theme.textMuted}[n/p]${theme.text}Track ${theme.textMuted}[h/l]${theme.text}±5s ${theme.textMuted}[H/L]${theme.text}±15s ${theme.textMuted}[[//]]${theme.text}Sync ${theme.textMuted}[//]${theme.text}Search ${theme.textMuted}[Tab]${theme.text}View ${theme.textMuted}[q]${theme.text}Quit${CliTheme.RESET}"
        val compact = "${theme.textMuted}[z]${theme.text}$zenLabel ${theme.textMuted}[t]${theme.text}Theme ${theme.textMuted}[Space]${theme.text}Play ${theme.textMuted}[j/k]${theme.text}Nav ${theme.textMuted}[s]${theme.text}Shuf ${theme.textMuted}[r]${theme.text}Rep ${theme.textMuted}[m]${theme.text}Mute ${theme.textMuted}[n/p]${theme.text}Track ${theme.textMuted}[h/l]${theme.text}Seek ${theme.textMuted}[//]${theme.text}Search ${theme.textMuted}[Tab]${theme.text}View ${theme.textMuted}[q]${theme.text}Quit${CliTheme.RESET}"
        val minimal = "${theme.textMuted}[z]${theme.text}$zenLabel ${theme.textMuted}[t]${theme.text}Theme ${theme.textMuted}[Space]${theme.text}Play ${theme.textMuted}[s]${theme.text}Shuf ${theme.textMuted}[r]${theme.text}Rep ${theme.textMuted}[//]${theme.text}Search ${theme.textMuted}[Tab]${theme.text}View ${theme.textMuted}[q]${theme.text}Quit${CliTheme.RESET}"

        val binds = when {
            width >= 120 -> full
            width >= 92 -> compact
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
