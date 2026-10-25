package com.roadconquest.app.forum

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class GifByteCacheTest {
    @Test fun sameDigestSharesOneInFlightDownload() {
        val cache = GifByteCache()
        val executor = Executors.newFixedThreadPool(2)
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val downloads = AtomicInteger()
        val bytes = byteArrayOf(1, 2, 3)
        try {
            val first = executor.submit<ByteArray> {
                cache.getOrLoad("same") {
                    downloads.incrementAndGet()
                    started.countDown()
                    check(finish.await(5, TimeUnit.SECONDS))
                    bytes
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val second = executor.submit<ByteArray> {
                cache.getOrLoad("same") { downloads.incrementAndGet(); byteArrayOf(9) }
            }
            finish.countDown()
            assertArrayEquals(bytes, first.get(5, TimeUnit.SECONDS))
            assertArrayEquals(bytes, second.get(5, TimeUnit.SECONDS))
            assertEquals(1, downloads.get())
        } finally {
            finish.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun differentDigestsDownloadInParallel() {
        val cache = GifByteCache()
        val executor = Executors.newFixedThreadPool(2)
        val bothStarted = CountDownLatch(2)
        val finish = CountDownLatch(1)
        try {
            fun request(key: String) = executor.submit<ByteArray> {
                cache.getOrLoad(key) {
                    bothStarted.countDown()
                    check(finish.await(5, TimeUnit.SECONDS))
                    key.toByteArray()
                }
            }
            val one = request("first")
            val two = request("second")
            assertTrue(bothStarted.await(5, TimeUnit.SECONDS))
            finish.countDown()
            assertArrayEquals("first".toByteArray(), one.get(5, TimeUnit.SECONDS))
            assertArrayEquals("second".toByteArray(), two.get(5, TimeUnit.SECONDS))
        } finally {
            finish.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun failedOrCancelledDownloadDoesNotPoisonFutureAttempts() {
        val cache = GifByteCache()
        var downloads = 0
        assertThrows(IllegalStateException::class.java) {
            cache.getOrLoad("key") { downloads++; error("Cancelled") }
        }
        assertArrayEquals(byteArrayOf(4), cache.getOrLoad("key") {
            downloads++
            byteArrayOf(4)
        })
        assertEquals(2, downloads)
        assertArrayEquals(byteArrayOf(4), cache.getOrLoad("key") { error("Should be cached") })
    }

    @Test fun cacheRetainsOnlyFourEntriesAndClearsOnClose() {
        val cache = GifByteCache()
        for (index in 0 until 5) {
            assertArrayEquals(byteArrayOf(index.toByte()), cache.getOrLoad("image-$index") {
                byteArrayOf(index.toByte())
            })
        }
        assertArrayEquals(byteArrayOf(7), cache.getOrLoad("image-0") { byteArrayOf(7) })
        cache.clear()
        assertArrayEquals(byteArrayOf(8), cache.getOrLoad("image-0") { byteArrayOf(8) })
    }
}
