package io.github.timefreak7.fileforge.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

class FdRandomAccessTest {
    private val data = Random(42).nextBytes(300_000) // spans several 64 KB windows
    private val file = File.createTempFile("fdra", ".bin").apply { writeBytes(data); deleteOnExit() }

    private fun open() = FdRandomAccess(RandomAccessFile(file, "r").channel)

    @Test fun sequentialReadMatchesFile() {
        open().use { r ->
            val out = ByteArray(data.size)
            var done = 0
            while (true) {
                val n = r.read(out, done, minOf(7_777, out.size - done))
                if (n <= 0) break
                done += n
            }
            assertEquals(data.size, done)
            assertArrayEquals(data, out)
            assertTrue(r.isEOF)
            assertEquals(-1, r.read())
        }
    }

    @Test fun randomSeeksAcrossWindows() {
        val random = Random(7)
        open().use { r ->
            repeat(2_000) {
                val pos = random.nextLong(data.size.toLong())
                r.seek(pos)
                assertEquals(data[pos.toInt()].toInt() and 0xFF, r.read())
                assertEquals(pos + 1, r.position)
            }
        }
    }

    @Test fun peekRewindAndReadFully() {
        open().use { r ->
            r.seek(65_530) // straddles the first window boundary
            val peeked = r.peek()
            assertEquals(65_530, r.position)
            assertEquals(peeked, r.read())
            r.rewind(1)
            assertArrayEquals(data.copyOfRange(65_530, 65_550), r.readFully(20))
            assertEquals(data.size - 65_550, r.available())
            assertEquals(data.size.toLong(), r.length())
        }
    }

    @Test(expected = java.io.EOFException::class) fun readFullyPastEndThrows() {
        open().use { r ->
            r.seek(data.size - 5L)
            r.readFully(10)
        }
    }
}
