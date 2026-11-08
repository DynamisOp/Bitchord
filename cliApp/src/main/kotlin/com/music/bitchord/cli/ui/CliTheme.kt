package com.music.bitchord.cli.ui

/**
 * Terminal color theme definitions for BitChord CLI.
 * Supports 24-bit TrueColor and classic ANSI fallbacks.
 */
data class CliTheme(
    val id: String,
    val name: String,
    val primary: String,       // Title, active tab, accents
    val secondary: String,     // Subtitle, lyrics header, artist
    val accent: String,        // Playing indicator, active highlights
    val highlightBg: String,   // Selection background (inverted / highlighted)
    val text: String,          // Primary text
    val textMuted: String,     // Inactive lyrics, borders, secondary info
    val border: String,        // Separator lines
    val visualizer: String,    // Audio spectrum visualizer bars
    val progressBar: String,   // Filled progress bar
    val progressEmpty: String, // Empty progress bar
    val warning: String,       // Buffering, alerts, sync offset
) {
    companion object {
        val RESET = "\u001B[0m"

        val CYBERPUNK = CliTheme(
            id = "cyberpunk",
            name = "Neon Cyberpunk (Default)",
            primary = "\u001B[1;38;2;0;240;255m",        // Bright Cyan
            secondary = "\u001B[1;38;2;255;90;190m",     // Neon Magenta
            accent = "\u001B[1;38;2;0;255;150m",        // Neon Green
            highlightBg = "\u001B[1;7;38;2;0;240;255m",
            text = "\u001B[38;2;230;235;245m",
            textMuted = "\u001B[38;2;110;115;130m",
            border = "\u001B[38;2;60;65;80m",
            visualizer = "\u001B[1;38;2;0;240;255m",
            progressBar = "\u001B[38;2;0;255;150m",
            progressEmpty = "\u001B[38;2;50;55;70m",
            warning = "\u001B[1;38;2;255;190;0m",
        )

        val CATPPUCCIN = CliTheme(
            id = "catppuccin",
            name = "Catppuccin Mocha",
            primary = "\u001B[1;38;2;203;166;247m",      // Mauve
            secondary = "\u001B[1;38;2;180;190;254m",    // Lavender
            accent = "\u001B[1;38;2;166;227;161m",       // Green
            highlightBg = "\u001B[1;7;38;2;203;166;247m",
            text = "\u001B[38;2;205;214;244m",           // Text
            textMuted = "\u001B[38;2;127;132;156m",      // Subtext0
            border = "\u001B[38;2;69;71;90m",            // Surface1
            visualizer = "\u001B[1;38;2;137;180;250m",   // Blue
            progressBar = "\u001B[38;2;166;227;161m",    // Green
            progressEmpty = "\u001B[38;2;49;50;68m",     // Surface0
            warning = "\u001B[1;38;2;249;226;175m",      // Peach
        )

        val TOKYO_NIGHT = CliTheme(
            id = "tokyo_night",
            name = "Tokyo Night",
            primary = "\u001B[1;38;2;122;162;247m",      // Electric Blue
            secondary = "\u001B[1;38;2;187;154;247m",    // Purple
            accent = "\u001B[1;38;2;158;206;106m",       // Neon Green
            highlightBg = "\u001B[1;7;38;2;122;162;247m",
            text = "\u001B[38;2;192;202;245m",
            textMuted = "\u001B[38;2;86;95;137m",
            border = "\u001B[38;2;41;46;66m",
            visualizer = "\u001B[1;38;2;125;207;255m",   // Cyan
            progressBar = "\u001B[38;2;158;206;106m",
            progressEmpty = "\u001B[38;2;36;40;59m",
            warning = "\u001B[1;38;2;224;175;104m",      // Orange/Yellow
        )

        val DRACULA = CliTheme(
            id = "dracula",
            name = "Dracula",
            primary = "\u001B[1;38;2;255;121;198m",      // Pink
            secondary = "\u001B[1;38;2;189;147;249m",    // Purple
            accent = "\u001B[1;38;2;80;250;123m",        // Green
            highlightBg = "\u001B[1;7;38;2;255;121;198m",
            text = "\u001B[38;2;248;248;242m",
            textMuted = "\u001B[38;2;98;114;164m",
            border = "\u001B[38;2;68;71;90m",
            visualizer = "\u001B[1;38;2;139;233;253m",   // Cyan
            progressBar = "\u001B[38;2;80;250;123m",
            progressEmpty = "\u001B[38;2;40;42;54m",
            warning = "\u001B[1;38;2;241;250;140m",      // Yellow
        )

        val NORD = CliTheme(
            id = "nord",
            name = "Nord Arctic",
            primary = "\u001B[1;38;2;136;192;208m",      // Frost Ice
            secondary = "\u001B[1;38;2;129;161;193m",    // Frost Blue
            accent = "\u001B[1;38;2;163;190;140m",       // Aurora Green
            highlightBg = "\u001B[1;7;38;2;136;192;208m",
            text = "\u001B[38;2;236;239;244m",           // Snow Storm
            textMuted = "\u001B[38;2;90;105;125m",
            border = "\u001B[38;2;59;66;82m",
            visualizer = "\u001B[1;38;2;143;188;187m",   // Frost Mint
            progressBar = "\u001B[38;2;163;190;140m",
            progressEmpty = "\u001B[38;2;46;52;64m",
            warning = "\u001B[1;38;2;235;203;139m",      // Aurora Yellow
        )

        val MATRIX = CliTheme(
            id = "matrix",
            name = "Emerald Matrix",
            primary = "\u001B[1;38;2;0;255;102m",        // Electric Green
            secondary = "\u001B[1;38;2;51;204;102m",     // Matrix Mid Green
            accent = "\u001B[1;38;2;102;255;178m",       // Mint
            highlightBg = "\u001B[1;7;38;2;0;255;102m",
            text = "\u001B[38;2;204;255;204m",
            textMuted = "\u001B[38;2;40;110;60m",
            border = "\u001B[38;2;20;60;30m",
            visualizer = "\u001B[1;38;2;0;255;102m",
            progressBar = "\u001B[38;2;0;255;102m",
            progressEmpty = "\u001B[38;2;15;45;20m",
            warning = "\u001B[1;38;2;204;255;0m",
        )

        val SOLARIZED = CliTheme(
            id = "solarized",
            name = "Amber Sunset",
            primary = "\u001B[1;38;2;255;165;0m",        // Amber
            secondary = "\u001B[1;38;2;211;54;130m",     // Magenta/Coral
            accent = "\u001B[1;38;2;42;161;152m",        // Cyan
            highlightBg = "\u001B[1;7;38;2;255;165;0m",
            text = "\u001B[38;2;253;246;227m",
            textMuted = "\u001B[38;2;101;123;131m",
            border = "\u001B[38;2;45;65;70m",
            visualizer = "\u001B[1;38;2;255;165;0m",
            progressBar = "\u001B[38;2;181;137;0m",      // Gold
            progressEmpty = "\u001B[38;2;25;45;50m",
            warning = "\u001B[1;38;2;203;75;22m",        // Orange
        )

        val CLASSIC = CliTheme(
            id = "classic",
            name = "Classic 16-Color ANSI",
            primary = "\u001B[1;36m",
            secondary = "\u001B[1;35m",
            accent = "\u001B[1;32m",
            highlightBg = "\u001B[1;7;36m",
            text = "\u001B[37m",
            textMuted = "\u001B[90m",
            border = "\u001B[90m",
            visualizer = "\u001B[1;36m",
            progressBar = "\u001B[32m",
            progressEmpty = "\u001B[90m",
            warning = "\u001B[1;33m",
        )

        val ALL = listOf(
            CYBERPUNK,
            CATPPUCCIN,
            TOKYO_NIGHT,
            DRACULA,
            NORD,
            MATRIX,
            SOLARIZED,
            CLASSIC,
        )

        fun find(id: String?): CliTheme = ALL.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: CYBERPUNK
    }
}
