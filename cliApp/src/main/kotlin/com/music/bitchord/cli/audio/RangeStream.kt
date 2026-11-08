package com.music.bitchord.cli.audio

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class RangeStream(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val windowBytes: Int = DEFAULT_WINDOW,
) {
    var length: Long = -1L
        private set

    private var position = 0L
    private var window = ByteArray(0)
    private var windowStart = -1L

    fun position(): Long = position

    fun seek(offset: Long) {
        position = offset.coerceAtLeast(0)
    }

    fun read(into: ByteArray, count: Int): Int {
        if (count <= 0) return 0
        if (length >= 0 && position >= length) return -1
        if (!holds(position) && !fetchWindowFor(position)) return -1
        val offset = (position - windowStart).toInt()
        val available = window.size - offset
        if (available <= 0) return -1
        val taken = minOf(count, available)
        System.arraycopy(window, offset, into, 0, taken)
        position += taken
        return taken
    }

    private fun holds(at: Long): Boolean =
        windowStart >= 0 && at >= windowStart && at < windowStart + window.size

    private fun fetchWindowFor(at: Long): Boolean {
        val start = at - (at % windowBytes)
        val end = start + windowBytes - 1
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Range", "bytes=$start-$end")
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
        headers.forEach { (name, value) -> runCatching { request.header(name, value) } }

        val response = runCatching {
            client.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        }.getOrNull() ?: return false

        val status = response.statusCode()
        if (status !in 200..299) return false
        val bytes = response.body() ?: return false
        if (bytes.isEmpty()) return false

        var actualStart = start
        if (status == 200) {
            actualStart = 0
            length = bytes.size.toLong()
        } else {
            response.headers().firstValue("Content-Range").ifPresent { rangeHeader ->
                val rangePart = rangeHeader.substringAfter("bytes ").substringBefore('/')
                val s = rangePart.substringBefore('-').trim().toLongOrNull()
                if (s != null) actualStart = s
                rangeHeader.substringAfterLast('/', "").toLongOrNull()?.let { length = it }
            }
        }

        if (length < 0) {
            response.headers().firstValueAsLong("Content-Length").ifPresent { length = it }
        }

        window = bytes
        windowStart = actualStart
        return true
    }

    companion object {
        private const val DEFAULT_WINDOW = 2 * 1024 * 1024 // 2MB
        private val client by lazy {
            HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build()
        }
    }
}
