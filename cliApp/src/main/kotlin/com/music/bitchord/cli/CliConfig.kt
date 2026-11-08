package com.music.bitchord.cli

import com.music.bitchord.cli.ui.CliTheme
import com.music.bitchord.cli.ui.TuiLayout
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

object CliConfig {
    private val configFile: File by lazy {
        val dir = File(System.getProperty("user.home"), ".cache/bitchord-cli")
        if (!dir.exists()) dir.mkdirs()
        File(dir, "cli-config.properties")
    }

    private val props = Properties()

    init {
        load()
    }

    private fun load() {
        if (configFile.exists()) {
            runCatching {
                FileInputStream(configFile).use { props.load(it) }
            }
        }
    }

    private fun save() {
        runCatching {
            FileOutputStream(configFile).use { props.store(it, "BitChord CLI User Preferences") }
        }
    }

    var themeId: String
        get() = props.getProperty("theme", CliTheme.CYBERPUNK.id)
        set(value) {
            props.setProperty("theme", value)
            save()
        }

    var layoutMode: TuiLayout
        get() {
            val v = props.getProperty("layout", TuiLayout.SPLIT.name)
            return runCatching { TuiLayout.valueOf(v) }.getOrDefault(TuiLayout.SPLIT)
        }
        set(value) {
            props.setProperty("layout", value.name)
            save()
        }
}
