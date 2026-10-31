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
        assertEquals("a.b_p001.jpg", FileNames.outputName("a.b.pdf", Format.JPG, suffix = "_p001"))
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
        assertEquals(Format.PDF, Sniffer.sniff(ascii("%PDF-1.7\n%âãÏÓ")))
    }

    @Test fun rejectsUnknownAndShortInput() {
        assertNull(Sniffer.sniff(ByteArray(0)))
        assertNull(Sniffer.sniff(byteArrayOf(0xFF.toByte())))
        assertNull(Sniffer.sniff(ascii("%PDX-1.7")))
        assertNull(Sniffer.sniff(ascii("\u0000\u0000\u0000\u0018ftypisom")))
    }
}

class TargetsTest {
    private val order = Format.DEFAULT_ORDER

    @Test fun heicNeedsHardwareEncoder() {
        assertEquals(listOf(Format.JPG, Format.PNG, Format.WEBP, Format.PDF), Targets.forInputs(listOf(Format.JPG), order, false))
        assertEquals(order, Targets.forInputs(listOf(Format.HEIC, Format.PNG), order, true))
    }

    @Test fun pdfInputsBecomePagesOrOnePdf() {
        assertEquals(listOf(Format.JPG, Format.PNG, Format.PDF), Targets.forInputs(listOf(Format.PDF), order, true))
        assertEquals(listOf(Format.JPG, Format.PNG, Format.PDF), Targets.forInputs(listOf(Format.PDF, Format.PDF), order, true))
        assertEquals("Compress", Targets.action(listOf(Format.PDF), Format.PDF))
        assertEquals("Merge", Targets.action(listOf(Format.PDF, Format.PDF), Format.PDF))
        assertEquals("JPG pages", Targets.action(listOf(Format.PDF), Format.JPG))
        assertEquals("PDF", Targets.action(listOf(Format.JPG, Format.PNG), Format.PDF))
    }

    @Test fun mixedImagesAndPdfsFitNothing() {
        assertTrue(Targets.forInputs(listOf(Format.JPG, Format.PDF), order, true).isEmpty())
    }

    @Test fun unknownOrEmptyInputFitsNothing() {
        assertTrue(Targets.forInputs(emptyList(), order, true).isEmpty())
        assertTrue(Targets.forInputs(listOf(Format.JPG, null), order, true).isEmpty())
    }

    @Test fun followsUserOrder() {
        val custom = listOf(Format.WEBP, Format.JPG, Format.HEIC, Format.PNG, Format.PDF)
        assertEquals(custom, Targets.forInputs(listOf(Format.PNG), custom, true))
    }
}

class LimitsTest {
    @Test fun timeoutScalesWithSizeAndIsCapped() {
        assertEquals(30_000L, Limits.imageTimeoutMs(0))
        assertEquals(50_000L, Limits.imageTimeoutMs(10L * 1024 * 1024))
        assertEquals(300_000L, Limits.imageTimeoutMs(Limits.MAX_IMAGE_BYTES))
    }

    @Test fun pdfTimeoutScalesWithPagesAndIsCapped() {
        assertEquals(60_000L, Limits.pdfTimeoutMs(0, 0))
        assertEquals(660_000L, Limits.pdfTimeoutMs(300, 0))
        assertEquals(1_800_000L, Limits.pdfTimeoutMs(Limits.MAX_PDF_PAGES, Limits.MAX_PDF_BYTES))
    }

    @Test(expected = IllegalArgumentException::class) fun presetRejectsBadDpi() {
        Preset(dpi = 10)
    }

    @Test(expected = IllegalArgumentException::class) fun presetRejectsBadQuality() {
        Preset(quality = 0)
    }
}

class PageGeometryTest {
    private fun map(m: FloatArray, x: Float, y: Float) = (m[0] * x + m[2] * y + m[4]) to (m[1] * x + m[3] * y + m[5])

    /** The display corner shown top-left must be the user-space corner that a clockwise /Rotate brings there. */
    @Test fun topLeftCornerFollowsClockwiseRotation() {
        val (x0, y0, w, h) = listOf(10f, 20f, 600f, 800f)
        val expected = mapOf(
            0 to (x0 to y0 + h), // unrotated: top-left
            90 to (x0 to y0), // bottom-left turns to top-left
            180 to (x0 + w to y0), // bottom-right
            270 to (x0 + w to y0 + h), // top-right
        )
        for ((rotation, corner) in expected) {
            val (dw, dh) = PageGeometry.displaySize(w, h, rotation)
            assertEquals("rotation $rotation", corner, map(PageGeometry.displayToUser(x0, y0, w, h, rotation), 0f, dh))
            // The opposite display corner lands on the opposite user corner.
            val (bx, by) = map(PageGeometry.displayToUser(x0, y0, w, h, rotation), dw, 0f)
            assertEquals(x0 + x0 + w, corner.first + bx, 0.001f)
            assertEquals(y0 + y0 + h, corner.second + by, 0.001f)
        }
        assertEquals(800f to 600f, PageGeometry.displaySize(600f, 800f, -90))
    }
}
