package com.music.bitchord.cli.ui

import com.music.bitchord.cli.CliConfig
import com.music.bitchord.cli.CliFavorites
import com.music.bitchord.cli.lyrics.CliLyrics
import com.music.bitchord.cli.player.CliPlayer
import com.music.bitchord.cli.player.CliRepeatMode
import com.music.bitchord.data.model.Song
import org.jline.terminal.Terminal
import java.io.PrintWriter

enum class TuiView(val label: String) {
    SEARCH("SEARCH"),
    QUEUE("QUEUE"),
    FAVORITES("FAVORITES"),
}

enum class TuiLayout(val label: String) {
    SPLIT("Split View (Cards)"),
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
    var selectedFavoritesIndex: Int = 0
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

    fun renderBoxTop(title: String, width: Int, rightMeta: String = ""): String {
        val rightPart = if (AnsiUtils.stripAnsi(rightMeta).isNotBlank()) " $rightMeta " else ""
        val rightW = AnsiUtils.visualWidth(rightPart)
        val maxTitleW = (width - 4 - rightW - 2).coerceAtLeast(0)
        val clippedTitle = if (AnsiUtils.visualWidth(title) > maxTitleW) AnsiUtils.clipToWidth(title, maxTitleW) else title
        val titlePart = if (AnsiUtils.stripAnsi(clippedTitle).isNotBlank()) " $clippedTitle " else ""
        val usedW = 4 + AnsiUtils.visualWidth(titlePart) + rightW
        val fillW = (width - usedW).coerceAtLeast(0)
        val line = "${theme.border}╭─${CliTheme.RESET}$titlePart${theme.border}${"─".repeat(fillW)}${CliTheme.RESET}$rightPart${theme.border}─╮${CliTheme.RESET}"
        return AnsiUtils.fitToWidth(line, width)
    }

    fun renderBoxLine(content: String, width: Int): String {
        val innerWidth = (width - 4).coerceAtLeast(1)
        val fitted = AnsiUtils.fitToWidth(content, innerWidth)
        return "${theme.border}│${CliTheme.RESET} $fitted ${theme.border}│${CliTheme.RESET}"
    }

    fun renderBoxBottom(width: Int, keybinds: String = ""): String {
        val maxBindsW = (width - 6).coerceAtLeast(0)
        val clippedBinds = if (AnsiUtils.visualWidth(keybinds) > maxBindsW) AnsiUtils.clipToWidth(keybinds, maxBindsW) else keybinds
        val bindsPart = if (AnsiUtils.stripAnsi(clippedBinds).isNotBlank()) " $clippedBinds " else ""
        val usedW = 4 + AnsiUtils.visualWidth(bindsPart)
        val fillW = (width - usedW).coerceAtLeast(0)
        val line = "${theme.border}╰─${CliTheme.RESET}$bindsPart${theme.border}${"─".repeat(fillW)}${CliTheme.RESET}${theme.border}─╯${CliTheme.RESET}"
        return AnsiUtils.fitToWidth(line, width)
    }

    fun render() {
        val size = terminal.size
        val width = if (size.columns > 0) size.columns else 80
        val height = if (size.rows > 0) size.rows else 24

        // Prevent right-edge terminal autowrap jitter
        val cardWidth = (width - 1).coerceAtLeast(20)

        val rows = ArrayList<String>(height)

        // 1. Top Header (1 row)
        rows.add(buildHeader(cardWidth))

        // 2. Body panels (innerHeight + 2 rows: Top border, inner lines, Bottom border)
        // Player deck takes 4 rows (Top border, Line 1, Line 2, Bottom border)
        // Total fixed rows = 1 (header) + 1 (body top) + 1 (body bottom) + 4 (player) = 7
        val innerHeight = (height - 7).coerceAtLeast(3)

        val leftTitle = when (currentView) {
            TuiView.SEARCH -> "${theme.primary}[1] Search${CliTheme.RESET}"
            TuiView.QUEUE -> "${theme.primary}[2] Playback Queue (${player.queue.size})${CliTheme.RESET}"
            TuiView.FAVORITES -> "\u001B[1;38;2;255;75;130m[3] Favorites (${CliFavorites.count()})${CliTheme.RESET}"
        }

        val syncNotice = lyrics.offsetText.value?.let { "${theme.warning}(sync $it)${CliTheme.RESET}" } ?: ""
        val lyricsLoading = if (lyrics.isLoading.value) "${theme.textMuted}(loading...)${CliTheme.RESET}" else ""
        val rightMeta = listOf(syncNotice, lyricsLoading).filter { it.isNotBlank() }.joinToString(" ")

        when (layoutMode) {
            TuiLayout.ZEN -> {
                // Queue minimized: Lyrics card spans full width
                val innerZenWidth = (cardWidth - 4).coerceAtLeast(10)
                val zenLines = renderRightPane(innerZenWidth, innerHeight, isZen = true)
                rows.add(renderBoxTop(
                    title = "${theme.secondary}Synced Lyrics${CliTheme.RESET}",
                    width = cardWidth,
                    rightMeta = "${theme.primary}[Zen Mode · z to restore]${CliTheme.RESET} $rightMeta".trim()
                ))
                for (r in 0 until innerHeight) {
                    rows.add(renderBoxLine(zenLines.getOrElse(r) { "" }, cardWidth))
                }
                rows.add(renderBoxBottom(cardWidth))
            }
            TuiLayout.QUEUE_ONLY -> {
                // Full-width Queue / Search / Favorites card
                val innerFullWidth = (cardWidth - 4).coerceAtLeast(10)
                val leftLines = renderLeftPane(innerFullWidth, innerHeight)
                rows.add(renderBoxTop(title = leftTitle, width = cardWidth))
                for (r in 0 until innerHeight) {
                    rows.add(renderBoxLine(leftLines.getOrElse(r) { "" }, cardWidth))
                }
                rows.add(renderBoxBottom(cardWidth))
            }
            TuiLayout.SPLIT -> {
                // Split Card layout: Left Card + Gap + Right Card
                val gap = 1
                val leftCardWidth = ((cardWidth - gap) / 2).coerceAtLeast(12)
                val rightCardWidth = (cardWidth - gap - leftCardWidth).coerceAtLeast(12)

                val innerLeftWidth = (leftCardWidth - 4).coerceAtLeast(8)
                val innerRightWidth = (rightCardWidth - 4).coerceAtLeast(8)

                val leftLines = renderLeftPane(innerLeftWidth, innerHeight)
                val rightLines = renderRightPane(innerRightWidth, innerHeight, isZen = false)

                val leftTop = renderBoxTop(leftTitle, leftCardWidth)
                val rightTop = renderBoxTop("${theme.secondary}Synced Lyrics${CliTheme.RESET}", rightCardWidth, rightMeta)
                rows.add("$leftTop $rightTop")

                for (r in 0 until innerHeight) {
                    val leftLine = renderBoxLine(leftLines.getOrElse(r) { "" }, leftCardWidth)
                    val rightLine = renderBoxLine(rightLines.getOrElse(r) { "" }, rightCardWidth)
                    rows.add("$leftLine $rightLine")
                }

                val leftBottom = renderBoxBottom(leftCardWidth)
                val rightBottom = renderBoxBottom(rightCardWidth)
                rows.add("$leftBottom $rightBottom")
            }
        }

        // 3. Player Deck Card (4 rows: Top border, Line 1, Line 2, Bottom border)
        val playerInnerWidth = (cardWidth - 4).coerceAtLeast(10)
        val codecInfo = player.streamInfo.value?.let { "${theme.border}[${theme.textMuted}$it${theme.border}]${CliTheme.RESET}" } ?: ""

        rows.add(renderBoxTop(
            title = "${theme.primary}Now Playing${CliTheme.RESET}",
            width = cardWidth,
            rightMeta = codecInfo
        ))
        rows.add(renderBoxLine(buildPlayerDeckLine1(playerInnerWidth), cardWidth))
        rows.add(renderBoxLine(buildPlayerDeckLine2(playerInnerWidth), cardWidth))
        rows.add(renderBoxBottom(cardWidth, keybinds = buildPlayerKeybinds(playerInnerWidth)))

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

        val qCount = player.queue.size
        val fCount = CliFavorites.count()
        val shufBadge = if (player.isShuffle.value) " ${theme.secondary}[🔀 SHUF]${CliTheme.RESET}" else ""
        val repBadge = when (player.repeatMode.value) {
            CliRepeatMode.ALL -> " ${theme.primary}[🔁 ALL]${CliTheme.RESET}"
            CliRepeatMode.ONE -> " ${theme.warning}[🔂 ONE]${CliTheme.RESET}"
            CliRepeatMode.OFF -> ""
        }

        val tabSearch = if (currentView == TuiView.SEARCH) "${theme.highlightBg} [1] SEARCH ${CliTheme.RESET}" else "${theme.textMuted}[1] SEARCH${CliTheme.RESET}"
        val tabQueue = if (currentView == TuiView.QUEUE) "${theme.highlightBg} [2] QUEUE ($qCount) ${CliTheme.RESET}" else "${theme.textMuted}[2] QUEUE ($qCount)${CliTheme.RESET}"
        val tabFav = if (currentView == TuiView.FAVORITES) "${theme.highlightBg} [3] FAV ($fCount) ${CliTheme.RESET}" else "${theme.textMuted}[3] FAV ($fCount)${CliTheme.RESET}"

        val viewTabs = when (layoutMode) {
            TuiLayout.ZEN -> "$tabSearch  $tabQueue  $tabFav ${theme.primary}[z: EXPAND]${CliTheme.RESET}$shufBadge$repBadge"
            TuiLayout.QUEUE_ONLY -> "$tabSearch  $tabQueue  $tabFav ${theme.secondary}[v: SPLIT]${CliTheme.RESET}$shufBadge$repBadge"
            TuiLayout.SPLIT -> "$tabSearch  $tabQueue  $tabFav ${theme.textMuted}[z: ZEN]${CliTheme.RESET}$shufBadge$repBadge"
        }

        val titleW = AnsiUtils.visualWidth(title)
        val tabsW = AnsiUtils.visualWidth(viewTabs)
        val spacing = (width - titleW - tabsW).coerceAtLeast(1)

        val combined = title + " ".repeat(spacing) + viewTabs
        return AnsiUtils.fitToWidth(combined, width)
    }

    private fun renderLeftPane(width: Int, height: Int): List<String> {
        val lines = mutableListOf<String>()

        when (currentView) {
            TuiView.SEARCH -> {
                val promptPrefix = if (isSearchingInput) {
                    "${theme.warning}Search/URL: ${theme.text}$searchQuery\u001B[7m ${CliTheme.RESET}"
                } else if (isSearchingNetwork) {
                    "${theme.primary}Fetching from YouTube Music: \"$searchQuery\"...${CliTheme.RESET}"
                } else if (searchQuery.isNotBlank()) {
                    "${theme.textMuted}Search: ${theme.text}$searchQuery ${theme.textMuted}(press / to edit)${CliTheme.RESET}"
                } else {
                    "${theme.textMuted}Press / to search songs, playlists (p:), or paste URL${CliTheme.RESET}"
                }
                lines.add(AnsiUtils.fitToWidth(promptPrefix, width))
                lines.add(theme.border + "┄".repeat((width - 1).coerceAtLeast(5)) + CliTheme.RESET)

                val listRows = height - 2
                if (searchResults.isEmpty()) {
                    lines.add(AnsiUtils.fitToWidth("  ${theme.textMuted}No search results. Press / to search songs or paste playlist URL.${CliTheme.RESET}", width))
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
                    val isFav = CliFavorites.isFavorite(song.videoId)
                    val favSlot = if (isFav) "\u001B[1;38;2;255;75;130m♥${CliTheme.RESET} " else "  "

                    val availableForTitle = (width - duration.length - 10).coerceAtLeast(8)
                    val fullTitle = "${song.title} - ${song.artist}"
                    val clipped = fullTitle.take(availableForTitle)

                    val line = if (isSelected) {
                        "${theme.highlightBg} > $favSlot$clipped$durPadding ${CliTheme.RESET}"
                    } else {
                        "   $favSlot${theme.text}$clipped${theme.textMuted}$durPadding${CliTheme.RESET}"
                    }
                    lines.add(AnsiUtils.fitToWidth(line, width))
                }
            }
            TuiView.QUEUE -> {
                val qHeader = "${theme.textMuted}Queue (${player.queue.size} songs) · [Enter]Play [a]Add [c]Clear${CliTheme.RESET}"
                lines.add(AnsiUtils.fitToWidth(qHeader, width))
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
                    val isFav = CliFavorites.isFavorite(song.videoId)
                    val favSlot = if (isFav) "\u001B[1;38;2;255;75;130m♥${CliTheme.RESET} " else "  "

                    val prefix = when {
                        isCurrent && isSelected -> "${theme.accent}▶>${CliTheme.RESET}"
                        isCurrent -> "${theme.accent} ▶${CliTheme.RESET}"
                        isSelected -> " >"
                        else -> "  "
                    }
                    val duration = song.durationText ?: ""
                    val durPadding = if (duration.isNotBlank()) " [$duration]" else ""
                    val availableForTitle = (width - duration.length - 12).coerceAtLeast(8)
                    val fullTitle = "${i + 1}. ${song.title} - ${song.artist}"
                    val clipped = fullTitle.take(availableForTitle)

                    val line = if (isSelected) {
                        "${theme.highlightBg}$prefix $favSlot$clipped$durPadding ${CliTheme.RESET}"
                    } else if (isCurrent) {
                        "$prefix $favSlot${theme.accent}$clipped${theme.textMuted}$durPadding${CliTheme.RESET}"
                    } else {
                        "$prefix $favSlot${theme.text}$clipped${theme.textMuted}$durPadding${CliTheme.RESET}"
                    }
                    lines.add(AnsiUtils.fitToWidth(line, width))
                }
            }
            TuiView.FAVORITES -> {
                val fHeader = "${theme.textMuted}Starred Favorites (${CliFavorites.count()} songs) · [Enter]Play [d]Remove${CliTheme.RESET}"
                lines.add(AnsiUtils.fitToWidth(fHeader, width))
                lines.add(theme.border + "┄".repeat((width - 1).coerceAtLeast(5)) + CliTheme.RESET)

                val listRows = height - 2
                val favs = CliFavorites.getFavorites()
                if (favs.isEmpty()) {
                    lines.add(AnsiUtils.fitToWidth("  ${theme.textMuted}No favorites saved yet.${CliTheme.RESET}", width))
                    lines.add(AnsiUtils.fitToWidth("  ${theme.textMuted}Press 'f' while playing any song to add it!${CliTheme.RESET}", width))
                    while (lines.size < height) lines.add("".padEnd(width))
                    return lines
                }

                val selected = selectedFavoritesIndex.coerceIn(0, favs.size - 1)
                val startIdx = (selected - listRows / 2).coerceAtLeast(0)
                    .coerceAtMost((favs.size - listRows).coerceAtLeast(0))

                for (i in startIdx until minOf(favs.size, startIdx + listRows)) {
                    val song = favs[i]
                    val isCurrent = (song.videoId == player.currentTrack.value?.videoId)
                    val isSelected = (i == selected)
                    val prefix = when {
                        isCurrent && isSelected -> "${theme.accent}▶>${CliTheme.RESET}"
                        isCurrent -> "${theme.accent} ▶${CliTheme.RESET}"
                        isSelected -> " >"
                        else -> "  "
                    }
                    val favSlot = "\u001B[1;38;2;255;75;130m♥${CliTheme.RESET} "
                    val duration = song.durationText ?: ""
                    val durPadding = if (duration.isNotBlank()) " [$duration]" else ""
                    val availableForTitle = (width - duration.length - 12).coerceAtLeast(8)
                    val fullTitle = "${i + 1}. ${song.title} - ${song.artist}"
                    val clipped = fullTitle.take(availableForTitle)

                    val line = if (isSelected) {
                        "${theme.highlightBg}$prefix $favSlot$clipped$durPadding ${CliTheme.RESET}"
                    } else if (isCurrent) {
                        "$prefix $favSlot${theme.accent}$clipped${theme.textMuted}$durPadding${CliTheme.RESET}"
                    } else {
                        "$prefix $favSlot${theme.text}$clipped${theme.textMuted}$durPadding${CliTheme.RESET}"
                    }
                    lines.add(AnsiUtils.fitToWidth(line, width))
                }
            }
        }

        while (lines.size < height) lines.add("".padEnd(width))
        return lines
    }

    private fun renderRightPane(width: Int, height: Int, isZen: Boolean): List<String> {
        val lines = mutableListOf<String>()
        val track = player.currentTrack.value

        val headerText = "${theme.textMuted}Real-time synced lyrics · [[/]] adjust sync offset${CliTheme.RESET}"
        lines.add(AnsiUtils.fitToWidth(headerText, width))
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
                // Large visualizer in center when no lyrics in Zen mode
                lines.add("")
                lines.add(AnsiUtils.fitToWidth("     ${theme.visualizer}♫ ${renderVisualizer(player.visualizerBands.value)}${CliTheme.RESET}", width))
            }
            while (lines.size < height) lines.add("".padEnd(width))
            return lines
        }

        // Center active lyric line
        val activeLine = curIdx.coerceIn(0, allLyrics.size - 1)
        val half = lyricRows / 2
        val startIdx = (activeLine - half).coerceAtLeast(0)
            .coerceAtMost((allLyrics.size - lyricRows).coerceAtLeast(0))

        val indent = if (isZen && width > 70) "      " else "  "

        for (i in startIdx until minOf(allLyrics.size, startIdx + lyricRows)) {
            val lyric = allLyrics[i]
            val isActive = (i == curIdx)
            val maxTextWidth = (width - indent.length - 8).coerceAtLeast(8)
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

    private fun buildPlayerDeckLine1(width: Int): String {
        val track = player.currentTrack.value
        val isPlaying = player.isPlaying.value
        val isBuffering = player.isBuffering.value

        val stateBadge = when {
            isBuffering -> "${theme.warning}[⏳ BUFFERING]${CliTheme.RESET}"
            isPlaying -> "${theme.accent}[▶ PLAYING]${CliTheme.RESET}"
            track != null -> "${theme.warning}[⏸ PAUSED]${CliTheme.RESET}"
            else -> "${theme.textMuted}[⏹ STOPPED]${CliTheme.RESET}"
        }

        val trackInfo = if (track != null) {
            "${theme.text}\u001B[1m${track.title}${CliTheme.RESET} ${theme.textMuted}•${CliTheme.RESET} ${theme.secondary}${track.artist}${CliTheme.RESET}"
        } else {
            "${theme.textMuted}Nothing playing — press / to search or 2 to view queue${CliTheme.RESET}"
        }

        val isFav = CliFavorites.isFavorite(track?.videoId)
        val favBadge = if (isFav) {
            " \u001B[1;38;2;255;75;130m♥ FAV${CliTheme.RESET}"
        } else if (track != null) {
            " ${theme.textMuted}♡${CliTheme.RESET}"
        } else ""

        val vizStr = if (isPlaying) {
            "  ${theme.visualizer}♫ ${renderVisualizer(player.visualizerBands.value)}${CliTheme.RESET}"
        } else ""

        val status = player.statusMessage.value?.let { " ${theme.warning}[$it]${CliTheme.RESET}" } ?: ""

        val line = "$stateBadge  $trackInfo$favBadge$vizStr$status"
        return AnsiUtils.fitToWidth(line, width)
    }

    private fun buildPlayerDeckLine2(width: Int): String {
        val posMs = player.positionMs.value
        val durMs = player.durationMs.value
        val volPct = (player.volume.value * 100).toInt()

        val posSec = posMs / 1000
        val durSec = durMs / 1000
        val posStr = formatTime(posSec)
        val durStr = formatTime(durSec)

        val progress = if (durSec > 0) (posSec.toDouble() / durSec).coerceIn(0.0, 1.0) else 0.0
        val pctInt = (progress * 100).toInt()
        val pctStr = "%2d%%".format(pctInt)

        val volBadge = if (player.isMuted.value) {
            "\u001B[1;31m[🔇 MUTED]${CliTheme.RESET}"
        } else {
            "${theme.textMuted}Vol: ${theme.text}$volPct%${CliTheme.RESET}"
        }

        val shufBadge = if (player.isShuffle.value) " ${theme.secondary}[⇄ SHUF]${CliTheme.RESET}" else ""
        val repBadge = when (player.repeatMode.value) {
            CliRepeatMode.ALL -> " ${theme.primary}[↻ ALL]${CliTheme.RESET}"
            CliRepeatMode.ONE -> " ${theme.warning}[↻ 1]${CliTheme.RESET}"
            CliRepeatMode.OFF -> ""
        }

        // Fixed-width parts: 0:39 [ ] 38% 4:33  [⇄ SHUF]  Vol: 80%
        val fixedText = "$posStr [] $pctStr $durStr$shufBadge$repBadge   $volBadge"
        val fixedW = AnsiUtils.visualWidth(fixedText)
        val barAvailable = (width - fixedW).coerceAtLeast(8)
        val filled = (progress * barAvailable).toInt().coerceIn(0, barAvailable)
        val empty = (barAvailable - filled).coerceAtLeast(0)

        val bar = theme.progressBar + "█".repeat(filled) + theme.progressEmpty + "░".repeat(empty) + CliTheme.RESET
        val line = "$posStr [$bar] ${theme.primary}$pctStr${CliTheme.RESET} $durStr$shufBadge$repBadge   $volBadge"
        return AnsiUtils.fitToWidth(line, width)
    }

    private fun buildPlayerKeybinds(width: Int): String {
        fun k(key: String, label: String): String =
            "${theme.border}[${theme.primary}$key${theme.border}]${theme.text}$label${CliTheme.RESET}"

        val zenLabel = if (layoutMode == TuiLayout.ZEN) "Queue" else "Zen"

        val full = listOf(
            k("Space", "Play"),
            k("f", "Fav"),
            k("z", zenLabel),
            k("t", "Theme"),
            k("j/k", "Nav"),
            k("Enter", "Select"),
            k("s", "Shuf"),
            k("r", "Rep"),
            k("m", "Mute"),
            k("a", "Queue"),
            k("n/p", "Track"),
            k("h/l", "±5s"),
            k("[/]", "Sync"),
            k("/", "Search"),
            k("Tab", "Tabs"),
            k("q", "Quit"),
        ).joinToString(" ")

        val medium = listOf(
            k("Space", "Play"),
            k("f", "Fav"),
            k("z", zenLabel),
            k("t", "Theme"),
            k("j/k", "Nav"),
            k("Enter", "Select"),
            k("s", "Shuf"),
            k("r", "Rep"),
            k("m", "Mute"),
            k("/", "Search"),
            k("Tab", "Tabs"),
            k("q", "Quit"),
        ).joinToString(" ")

        val compact = listOf(
            k("Space", "Play"),
            k("f", "Fav"),
            k("z", zenLabel),
            k("t", "Theme"),
            k("s", "Shuf"),
            k("r", "Rep"),
            k("/", "Search"),
            k("Tab", "Tabs"),
            k("q", "Quit"),
        ).joinToString(" ")

        val minimal = listOf(
            k("Space", "Play"),
            k("f", "Fav"),
            k("z", zenLabel),
            k("t", "Theme"),
            k("Tab", "Tabs"),
            k("q", "Quit"),
        ).joinToString(" ")

        return when {
            width >= 130 -> full
            width >= 96 -> medium
            width >= 75 -> compact
            else -> minimal
        }
    }

    private fun formatTime(sec: Long): String {
        val m = sec / 60
        val s = sec % 60
        return "%d:%02d".format(m, s)
    }
}
