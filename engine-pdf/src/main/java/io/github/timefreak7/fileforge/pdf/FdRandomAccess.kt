package io.github.timefreak7.fileforge.pdf

import com.tom_roush.pdfbox.io.RandomAccessRead
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Lets PDFBox read straight from a file descriptor's channel, a window at a time. The isolated process
 * cannot create temp files, and loading a whole PDF into memory would not survive a 300-page file.
 */
class FdRandomAccess(private val channel: FileChannel) : RandomAccessRead {
    private val size = channel.size()
    private val window = ByteBuffer.allocate(64 * 1024)
    private var windowStart = 0L
    private var windowLength = 0
    private var position = 0L
    private var closed = false

    private fun fill() {
        if (position >= windowStart && position < windowStart + windowLength) return
        window.clear()
        windowStart = position
        windowLength = 0
        while (window.hasRemaining()) {
            val n = channel.read(window, windowStart + windowLength)
            if (n <= 0) break
            windowLength += n
        }
    }

    private fun checkOpen() {
        if (closed) throw IOException("closed")
    }

    override fun read(): Int {
        checkOpen()
        if (position >= size) return -1
        fill()
        val b = window.get((position - windowStart).toInt()).toInt() and 0xFF
        position++
        return b
    }

    override fun read(b: ByteArray): Int = read(b, 0, b.size)

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkOpen()
        if (len == 0) return 0
        if (position >= size) return -1
        var done = 0
        while (done < len && position < size) {
            fill()
            val inWindow = (position - windowStart).toInt()
            val n = minOf(len - done, windowLength - inWindow)
            if (n <= 0) break
            System.arraycopy(window.array(), inWindow, b, off + done, n)
            done += n
            position += n
        }
        return done
    }

    override fun getPosition(): Long = position

    override fun seek(position: Long) {
        checkOpen()
        if (position < 0) throw IOException("negative seek")
        this.position = position
    }

    override fun length(): Long = size

    override fun isClosed(): Boolean = closed

    override fun peek(): Int {
        val b = read()
        if (b != -1) position--
        return b
    }

    override fun rewind(bytes: Int) {
        seek(position - bytes)
    }

    override fun readFully(length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val n = read(out, done, length - done)
            if (n < 0) throw EOFException()
            done += n
        }
        return out
    }

    override fun isEOF(): Boolean = position >= size

    override fun available(): Int = (size - position).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    override fun close() {
        closed = true
    }
}
