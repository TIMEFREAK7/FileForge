package io.github.timefreak7.fileforge.spike

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Phase 0 spike: encodes 30 grey 720p frames with the first hardware H.264 encoder.
 * Run from the main process (control) and from the isolated process to see whether
 * hardware MediaCodec is reachable there.
 */
object EncodeProbe {
    private const val W = 1280
    private const val H = 720
    private const val FRAMES = 30
    private const val TIMEOUT_US = 10_000L

    fun run(): String = try {
        encode()
    } catch (t: Throwable) {
        "failed: ${t.javaClass.simpleName}: ${t.message}"
    }

    private fun encode(): String {
        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            it.isEncoder && it.isHardwareAccelerated && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes
        } ?: return "failed: no hardware H.264 encoder listed"

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createByCodecName(info.name)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val frame = ByteArray(W * H * 3 / 2) { 0x80.toByte() }
            val outInfo = MediaCodec.BufferInfo()
            var queued = 0
            var outBytes = 0L
            var done = false
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!done) {
                if (System.nanoTime() > deadline) return "failed: ${info.name} timed out after $queued frames"
                if (queued < FRAMES) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val n = minOf(frame.size, buf.remaining())
                        buf.put(frame, 0, n)
                        val flags = if (queued == FRAMES - 1) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        codec.queueInputBuffer(inIndex, 0, n, queued * 33_333L, flags)
                        queued++
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(outInfo, TIMEOUT_US)
                if (outIndex >= 0) {
                    outBytes += outInfo.size
                    done = outInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
            return if (outBytes > 0) "ok: ${info.name} produced $outBytes bytes from $FRAMES frames"
            else "failed: ${info.name} produced no output"
        } finally {
            codec.release()
        }
    }
}
