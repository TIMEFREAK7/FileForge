package io.github.timefreak7.fileforge.media

/** MP3 encoding through the bundled LAME library (libmp3lame.so, LGPL). */
object Mp3Encoder {
    init {
        System.loadLibrary("fileforge_mp3")
    }

    /** [pcm] is interleaved 16-bit samples; returns a complete MP3 stream. */
    fun encode(pcm: ShortArray, sampleRate: Int, channels: Int, bitrateKbps: Int = 192): ByteArray {
        require(channels == 1 || channels == 2) { "channels must be 1 or 2" }
        require(pcm.size % channels == 0) { "pcm length must be a multiple of channels" }
        return nativeEncode(pcm, sampleRate, channels, bitrateKbps)
            ?: throw IllegalStateException("LAME failed to encode")
    }

    @JvmStatic
    private external fun nativeEncode(pcm: ShortArray, sampleRate: Int, channels: Int, bitrateKbps: Int): ByteArray?
}
