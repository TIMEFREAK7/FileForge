package io.github.timefreak7.fileforge.media

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** Phase 0: the NDK-built LAME produces an MP3 that Android's own extractor reads back at the right length. */
@RunWith(AndroidJUnit4::class)
class Mp3EncoderTest {

    @Test fun oneSecondSineRoundTrips() {
        val rate = 44_100
        val pcm = ShortArray(rate * 2) { i ->
            (sin(2 * PI * 440 * (i / 2) / rate) * 12_000).toInt().toShort()
        }
        val mp3 = Mp3Encoder.encode(pcm, rate, channels = 2)
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "sine.mp3")
        file.writeBytes(mp3)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_MPEG, format.getString(MediaFormat.KEY_MIME))
            assertEquals(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            val durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000
            assertTrue("duration $durationMs ms", durationMs in 950..1150)
        } finally {
            extractor.release()
        }
    }
}
