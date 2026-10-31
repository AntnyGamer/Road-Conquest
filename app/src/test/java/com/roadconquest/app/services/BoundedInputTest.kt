package com.roadconquest.app.services

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class BoundedInputTest {
    private fun decode(input: InputStream, limit: Int) = readBoundedUtf8(input, limit)
    private class Chunked(bytes: ByteArray, private val chunk: Int) : ByteArrayInputStream(bytes) {
        var closed = false
        var readBytes = 0
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, minOf(chunk, length)).also { if (it > 0) readBytes += it }
        override fun close() { closed = true; super.close() }
    }

    @Test fun cancelledReadDoesNotConsumeAnyMoreResponseBytes() {
        val input = object : InputStream() {
            override fun read(): Int = error("Cancelled work must not read the response")
        }
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { decode(input, 4096) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun cancellationDuringTheFinalReadIsNotReturnedAsASuccessfulResponse() {
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int = error("Bulk reads expected")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                Thread.currentThread().interrupt()
                return -1
            }
        }
        try {
            assertThrows(InterruptedException::class.java) { decode(input, 4096) }
            assertEquals(1, reads)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun completeDecodedStringsMatchForUnicodeMalformedAndEveryBytePair() {
        val allPairs = ByteArray(131_072) { if (it % 2 == 0) (it / 512).toByte() else (it / 2).toByte() }
        val samples = listOf(byteArrayOf(), "ASCII\u0000驾驶🚙é".toByteArray(), ByteArray(256) { it.toByte() }, allPairs,
            ByteArray(8191) { 'x'.code.toByte() } + "🚙".toByteArray(),
            byteArrayOf(0xf0.toByte(), 0x90.toByte(), 0x80.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()))
        val digest = MessageDigest.getInstance("SHA-256")
        for (bytes in samples) for (chunk in listOf(1, 3, 8192)) {
            val input = Chunked(bytes, chunk)
            val result = decode(input, bytes.size)
            assertEquals(bytes.toString(Charsets.UTF_8), result)
            assertFalse("The caller still owns the stream", input.closed)
            digest.update(result.toByteArray(Charsets.UTF_8))
        }
        println("BOUNDED_UTF8 sdk=${RuntimeEnvironment.getApiLevel()} cases=21 sha256=" +
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    @Test fun byteLimitsKeepExactSuccessRejectionAndReadAmounts() {
        for (limit in listOf(-1, 0, 1, 8191, 8192, 8193, 4 * 1024 * 1024)) {
            for (size in setOf(0, limit.coerceAtLeast(0), (limit + 1).coerceAtLeast(1))) {
                val bytes = ByteArray(size) { 'x'.code.toByte() }
                val input = Chunked(bytes, 8192)
                if (size == 0 || size <= limit) {
                    assertEquals("x".repeat(size), decode(input, limit))
                    assertEquals(size, input.readBytes)
                } else {
                    val error = assertThrows(IOException::class.java) { decode(input, limit) }
                    assertEquals("Service response too large", error.message)
                    assertEquals(minOf(size, (limit.coerceAtLeast(0) / 8192 + 1) * 8192), input.readBytes)
                }
                assertFalse(input.closed)
            }
        }
        val failure = IOException("Fixture failure")
        val input = object : InputStream() { override fun read(): Int = throw failure }
        assertSame(failure, assertThrows(IOException::class.java) { decode(input, 4096) })
    }

    @Test fun boundedResponseAllocationFixture() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        for (size in listOf(128, 4096, 65_536, 1_048_576, 4_194_304)) {
            val bytes = ByteArray(size) { 'x'.code.toByte() }
            repeat(5) { decode(ByteArrayInputStream(bytes), size) }
            val rounds = LongArray(3) {
                val start = bean.getThreadAllocatedBytes(Thread.currentThread().id)
                repeat(5) { assertEquals(size, decode(ByteArrayInputStream(bytes), size).length) }
                bean.getThreadAllocatedBytes(Thread.currentThread().id) - start
            }
            println("BOUNDED_ALLOCATION sdk=${RuntimeEnvironment.getApiLevel()} bytes=$size calls=5 rounds=${rounds.contentToString()}")
        }
    }
}
