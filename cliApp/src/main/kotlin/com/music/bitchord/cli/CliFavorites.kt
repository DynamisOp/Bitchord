package com.music.bitchord.cli

import com.music.bitchord.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CliFavoriteRecord(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationText: String? = null,
    val thumbnailUrl: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)

object CliFavorites {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val favoritesFile: File by lazy {
        val dir = File(System.getProperty("user.home"), ".cache/bitchord-cli")
        if (!dir.exists()) dir.mkdirs()
        File(dir, "favorites.json")
    }

    private val favoritesList = mutableListOf<Song>()
    private val favoriteIds = mutableSetOf<String>()

    init {
        load()
    }

    @Synchronized
    private fun load() {
        if (!favoritesFile.exists()) return
        runCatching {
            val text = favoritesFile.readText()
            if (text.isNotBlank()) {
                val records = json.decodeFromString<List<CliFavoriteRecord>>(text)
                favoritesList.clear()
                favoriteIds.clear()
                for (r in records) {
                    val song = Song(
                        videoId = r.videoId,
                        title = r.title,
                        artist = r.artist,
                        thumbnailUrl = r.thumbnailUrl,
                        durationText = r.durationText,
                    )
                    favoritesList.add(song)
                    favoriteIds.add(song.videoId)
                }
            }
        }
    }

    @Synchronized
    private fun save() {
        runCatching {
            val records = favoritesList.map { song ->
                CliFavoriteRecord(
                    videoId = song.videoId,
                    title = song.title,
                    artist = song.artist,
                    durationText = song.durationText,
                    thumbnailUrl = song.thumbnailUrl,
                )
            }
            favoritesFile.writeText(json.encodeToString(records))
        }
    }

    @Synchronized
    fun isFavorite(videoId: String?): Boolean {
        if (videoId == null) return false
        return favoriteIds.contains(videoId)
    }

    @Synchronized
    fun toggleFavorite(song: Song): Boolean {
        return if (favoriteIds.contains(song.videoId)) {
            removeFavorite(song.videoId)
            false
        } else {
            addFavorite(song)
            true
        }
    }

    @Synchronized
    fun addFavorite(song: Song) {
        if (!favoriteIds.contains(song.videoId)) {
            favoriteIds.add(song.videoId)
            favoritesList.add(0, song)
            save()
        }
    }

    @Synchronized
    fun removeFavorite(videoId: String) {
        if (favoriteIds.remove(videoId)) {
            favoritesList.removeAll { it.videoId == videoId }
            save()
        }
    }

    @Synchronized
    fun getFavorites(): List<Song> = favoritesList.toList()

    @Synchronized
    fun count(): Int = favoritesList.size
}
