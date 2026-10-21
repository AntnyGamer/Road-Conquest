package com.roadconquest.app.map

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class CacheEvictionOrderTest {
    private class Entry(path: String, var modified: Long) : File(path) {
        var reads=0
        override fun lastModified(): Long { reads++; return modified }
    }
    @Test fun exactOldestOrderAndFilenameTiesPreserveOriginalFileObjects() {
        val files=listOf(Entry("a/z.json",5),Entry("b/a.json",5),Entry("a/a.json",5),
            Entry("a/new.json",Long.MAX_VALUE),Entry("a/old.json",Long.MIN_VALUE))
        val expected=files.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        files.forEach { it.reads=0 }
        val actual=PlaceOverlayCache.oldestCacheFiles(files)
        assertEquals(expected,actual)
        actual.forEachIndexed { i,file -> assertSame(expected[i],file) }
        assertTrue(files.all { it.reads==1 })
    }
    @Test fun sixThousandFilesNeedOneTimestampReadEachInsteadOfPerComparison() {
        val files=List(6000) { i -> Entry("cache/"+i.toString().padStart(5,'0')+".json", 1_500_000_000_000L+((i*1877)%6000)*1000L) }
        val expected=files.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        val before=files.sumOf { it.reads }
        files.forEach { it.reads=0 }
        assertEquals(expected,PlaceOverlayCache.oldestCacheFiles(files))
        assertEquals(6000,files.sumOf { it.reads })
        assertTrue(before>6000)
        println("CACHE_METADATA sdk=${android.os.Build.VERSION.SDK_INT} files=6000 readsBefore=$before readsAfter=6000")
    }
    @Test fun laterMaintenanceReadsFreshTimestampsInsteadOfKeepingAStaleOrder() {
        val a=Entry("a.json",1); val b=Entry("b.json",2)
        val files=listOf(a,b)
        assertEquals(listOf(a,b),PlaceOverlayCache.oldestCacheFiles(files))
        a.modified=3
        assertEquals(listOf(b,a),PlaceOverlayCache.oldestCacheFiles(files))
        assertEquals(2,a.reads); assertEquals(2,b.reads)
    }
    @Test fun emptyAndSingleFileMaintenanceNeedsNoMetadataSnapshot() {
        assertTrue(PlaceOverlayCache.oldestCacheFiles(emptyList()).isEmpty())
        val file=Entry("only.json",1)
        assertSame(file,PlaceOverlayCache.oldestCacheFiles(listOf(file)).single())
        assertEquals(0,file.reads)
    }
}
