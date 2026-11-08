package com.music.bitchord.cli.audio

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int
import org.bytedeco.ffmpeg.avformat.Seek_Pointer_long_int
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swresample.*
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer

class AudioDecoder : AutoCloseable {

    private var format: AVFormatContext? = null
    private var codec: AVCodecContext? = null
    private var resampler: SwrContext? = null
    private var packet: AVPacket? = null
    private var frame: AVFrame? = null

    private var streamIndex = -1
    private var streamTimeBase = 1.0
    private var outputBuffer: BytePointer? = null
    private var outputBufferSamples = 0
    private var outputPlanes: PointerPointer<BytePointer>? = null
    private var scratch = ByteArray(0)
    private var samples = FloatArray(0)
    private var drained = false

    private var rangeSource: RangeStream? = null
    private var avio: AVIOContext? = null
    private var readPacket: Read_packet_Pointer_BytePointer_int? = null
    private var seekPacket: Seek_Pointer_long_int? = null

    var outputFormat: PcmFormat = PcmFormat(44_100, 2, 2, isFloat = false)
        private set

    var durationUs: Long = 0L
        private set

    var positionUs: Long = 0L
        private set

    fun open(url: String, headers: Map<String, String> = emptyMap()): Result<Unit> = runCatching {
        close()
        av_log_set_level(AV_LOG_ERROR)
        avformat_network_init()

        val container: AVFormatContext
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            val source = RangeStream(url, headers)
            rangeSource = source
            val ioBuffer = ByteArray(32768)
            scratch = ioBuffer

            val readCb = object : Read_packet_Pointer_BytePointer_int() {
                override fun call(opaque: Pointer?, buffer: BytePointer?, size: Int): Int {
                    if (buffer == null) return AVERROR_EOF
                    val taken = source.read(scratch, minOf(size, scratch.size))
                    if (taken <= 0) return AVERROR_EOF
                    buffer.put(scratch, 0, taken)
                    return taken
                }
            }
            readPacket = readCb

            val seekCb = object : Seek_Pointer_long_int() {
                override fun call(opaque: Pointer?, offset: Long, whence: Int): Long {
                    if (whence == AVSEEK_SIZE) return source.length
                    val target = when (whence) {
                        1 -> source.position() + offset // SEEK_CUR
                        2 -> source.length + offset     // SEEK_END
                        else -> offset                  // SEEK_SET
                    }
                    if (target < 0) return -1
                    source.seek(target)
                    return target
                }
            }
            seekPacket = seekCb

            val avioBuffer = BytePointer(av_malloc(32768)).capacity(32768)
            val avioCtx = avio_alloc_context(avioBuffer, 32768, 0, null, readCb, null, seekCb)
                ?: error("Failed to allocate AVIO context")
            avio = avioCtx

            container = avformat_alloc_context() ?: error("Failed to allocate format context")
            container.pb(avioCtx)

            if (avformat_open_input(container, null as String?, null as org.bytedeco.ffmpeg.avformat.AVInputFormat?, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) < 0) {
                error("Could not open input stream via AVIO")
            }
        } else {
            val ctx = AVFormatContext(null)
            if (avformat_open_input(ctx, url, null, null) < 0) {
                error("Could not open file: $url")
            }
            container = ctx
        }
        format = container

        if (avformat_find_stream_info(container, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) < 0) {
            error("Could not find stream info")
        }

        val audioStreamIdx: Int = av_find_best_stream(
            container,
            AVMEDIA_TYPE_AUDIO,
            -1,
            -1,
            null as org.bytedeco.ffmpeg.avcodec.AVCodec?,
            0
        )
        if (audioStreamIdx < 0) error("No audio stream found")
        streamIndex = audioStreamIdx

        val stream = container.streams(audioStreamIdx)
        val tb = stream.time_base()
        streamTimeBase = if (tb.den() != 0) tb.num().toDouble() / tb.den() else 1.0

        val dur = container.duration()
        durationUs = if (dur > 0) dur else 0L

        val codecParams = stream.codecpar()
        val decoder = avcodec_find_decoder(codecParams.codec_id())
            ?: error("Codec not supported: ${codecParams.codec_id()}")

        val codecCtx = avcodec_alloc_context3(decoder) ?: error("Failed to alloc codec context")
        codec = codecCtx

        if (avcodec_parameters_to_context(codecCtx, codecParams) < 0) {
            error("Failed to copy codec params to context")
        }
        if (avcodec_open2(codecCtx, decoder, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) < 0) {
            error("Failed to open codec")
        }

        packet = av_packet_alloc()
        frame = av_frame_alloc()

        // Setup resampler to 44.1kHz Stereo 16-bit PCM
        val outLayout = AVChannelLayout()
        av_channel_layout_default(outLayout, 2)
        val swr = swr_alloc() ?: error("Failed to allocate SwrContext")
        swr_alloc_set_opts2(
            swr,
            outLayout,
            AV_SAMPLE_FMT_S16,
            44_100,
            codecCtx.ch_layout(),
            codecCtx.sample_fmt(),
            codecCtx.sample_rate(),
            0,
            null as Pointer?,
        )
        if (swr_init(swr) < 0) error("Failed to init SwrContext")
        resampler = swr

        outputFormat = PcmFormat(44_100, 2, 2, isFloat = false)
        drained = false
    }

    fun seekTo(seconds: Double): Boolean {
        val container = format ?: return false
        val targetPts = (seconds / streamTimeBase).toLong()
        val res = av_seek_frame(container, streamIndex, targetPts, AVSEEK_FLAG_BACKWARD)
        if (res >= 0) {
            codec?.let { avcodec_flush_buffers(it) }
            positionUs = (seconds * 1_000_000).toLong()
            drained = false
            return true
        }
        return false
    }

    /**
     * Reads and decodes up to one frame of audio, returning interleaved float samples.
     * Returns null when stream ends (EOF).
     */
    fun readSamples(): FloatArray? {
        val container = format ?: return null
        val codecCtx = codec ?: return null
        val pkt = packet ?: return null
        val frm = frame ?: return null
        val swr = resampler ?: return null

        while (true) {
            val rec = avcodec_receive_frame(codecCtx, frm)
            if (rec == 0) {
                // Convert frame to output format
                val outSamples = swr_get_out_samples(swr, frm.nb_samples())
                val bufferSize = outSamples * outputFormat.frameBytes
                if (outputBuffer == null || outputBufferSamples < outSamples) {
                    outputBuffer?.close()
                    val buf = BytePointer(bufferSize.toLong())
                    outputBuffer = buf
                    outputBufferSamples = outSamples
                    outputPlanes?.close()
                    outputPlanes = PointerPointer<BytePointer>(1L).apply {
                        put(0L, buf)
                    }
                }

                val converted = swr_convert(
                    swr,
                    outputPlanes,
                    outSamples,
                    frm.data(),
                    frm.nb_samples(),
                )
                if (converted > 0) {
                    val floatCount = converted * outputFormat.channels
                    if (samples.size < floatCount) samples = FloatArray(floatCount)

                    val pts = frm.pts()
                    if (pts != AV_NOPTS_VALUE) {
                        positionUs = (pts * streamTimeBase * 1_000_000).toLong()
                    }

                    // Convert S16 bytes from outputBuffer to floats in [-1.0, 1.0]
                    val ptr = outputBuffer ?: return null
                    for (i in 0 until floatCount) {
                        val low = ptr.get((i * 2).toLong()).toInt() and 0xFF
                        val high = ptr.get((i * 2 + 1).toLong()).toInt()
                        val s16 = (high shl 8) or low
                        samples[i] = s16 / 32768.0f
                    }
                    return samples.copyOf(floatCount)
                }
            } else if (rec == AVERROR_EOF) {
                return null
            }

            // Need more packets
            val readRes = av_read_frame(container, pkt)
            if (readRes < 0) {
                if (!drained) {
                    drained = true
                    avcodec_send_packet(codecCtx, null)
                } else {
                    return null
                }
            } else if (pkt.stream_index() == streamIndex) {
                avcodec_send_packet(codecCtx, pkt)
                av_packet_unref(pkt)
            } else {
                av_packet_unref(pkt)
            }
        }
    }

    override fun close() {
        outputBuffer?.close()
        outputBuffer = null
        outputPlanes?.close()
        outputPlanes = null

        frame?.let { av_frame_free(it) }
        frame = null
        packet?.let { av_packet_free(it) }
        packet = null
        resampler?.let { swr_free(it) }
        resampler = null
        codec?.let { avcodec_free_context(it) }
        codec = null
        format?.let { avformat_close_input(it) }
        format = null
        avio?.let { avio_context_free(it) }
        avio = null
        rangeSource = null
        readPacket = null
        seekPacket = null
    }
}
