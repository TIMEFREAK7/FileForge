package io.github.timefreak7.fileforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {
    @Test fun hostileNamesNeverBecomePaths() {
        val hostile = listOf(
            "../../etc/passwd", "a/b\\c", "..", ".", "", "   ", "con\u0000trol", "evil\u202Egpj.exe",
            "x".repeat(500), "/", "....jpg", "name\nwith\nnewlines", "C:\\Windows",
        )
        for (name in hostile) {
            val out = FileNames.sanitize(name)
            assertFalse("$name -> $out", out.contains('/') || out.contains('\\') || out.contains(".."))
            assertFalse("$name -> $out", out.any { it.isISOControl() || it == '\u202E' })
            assertTrue("$name -> $out", out.isNotEmpty() && out.length <= 100)
            assertFalse("$name -> $out", out.startsWith('.'))
        }
    }

    @Test fun outputNameKeepsBaseAndSwapsExtension() {
        assertEquals("IMG_1234.jpg", FileNames.outputName("IMG_1234.HEIC", Format.JPG))
        assertEquals("holiday.webp", FileNames.outputName("holiday", Format.WEBP))
        assertEquals("my photo.png", FileNames.outputName("x.jpg", Format.PNG, chosen = "my photo"))
        assertEquals("__etc_passwd.png", FileNames.outputName("../etc/passwd.jpg", Format.PNG))
        assertEquals("converted.jpg", FileNames.outputName(null, Format.JPG))
    }
}

class SnifferTest {
    private fun bytes(vararg v: Int) = ByteArray(16).also { v.forEachIndexed { i, b -> it[i] = b.toByte() } }
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1).copyOf(16)

    @Test fun identifiesByContentNotName() {
        assertEquals(Format.JPG, Sniffer.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(Format.PNG, Sniffer.sniff(bytes(0x89, 'P'.code, 'N'.code, 'G'.code)))
        assertEquals(Format.WEBP, Sniffer.sniff(ascii("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ")))
        assertEquals(Format.HEIC, Sniffer.sniff(ascii("\u0000\u0000\u0000\u0018ftypheic")))
        assertEquals(Format.HEIC, Sniffer.sniff(ascii("\u0000\u0000\u0000\u0018ftypmif1")))
    }

    @Test fun rejectsUnknownAndShortInput() {
        assertNull(Sniffer.sniff(ByteArray(0)))
        assertNull(Sniffer.sniff(byteArrayOf(0xFF.toByte())))
        assertNull(Sniffer.sniff(ascii("%PDF-1.7")))
        assertNull(Sniffer.sniff(ascii("\u0000\u0000\u0000\u0018ftypisom")))
    }
}

class TargetsTest {
    private val order = Format.DEFAULT_ORDER

    @Test fun heicNeedsHardwareEncoder() {
        assertEquals(listOf(Format.JPG, Format.PNG, Format.WEBP), Targets.forInputs(listOf(Format.JPG), order, false))
        assertEquals(order, Targets.forInputs(listOf(Format.HEIC, Format.PNG), order, true))
    }

    @Test fun unknownOrEmptyInputFitsNothing() {
        assertTrue(Targets.forInputs(emptyList(), order, true).isEmpty())
        assertTrue(Targets.forInputs(listOf(Format.JPG, null), order, true).isEmpty())
    }

    @Test fun followsUserOrder() {
        val custom = listOf(Format.WEBP, Format.JPG, Format.HEIC, Format.PNG)
        assertEquals(custom, Targets.forInputs(listOf(Format.PNG), custom, true))
    }
}

class LimitsTest {
    @Test fun timeoutScalesWithSizeAndIsCapped() {
        assertEquals(30_000L, Limits.imageTimeoutMs(0))
        assertEquals(50_000L, Limits.imageTimeoutMs(10L * 1024 * 1024))
        assertEquals(300_000L, Limits.imageTimeoutMs(Limits.MAX_IMAGE_BYTES))
    }

    @Test(expected = IllegalArgumentException::class) fun presetRejectsBadQuality() {
        Preset(quality = 0)
    }
}
