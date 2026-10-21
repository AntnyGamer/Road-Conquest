package com.roadconquest.app.data

import com.roadconquest.app.billing.VipEntitlement
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class GpxResourceTest {
    @Test fun exportedPointsKeepExactBytesAndPrivacyCutoffs() {
        val context = RuntimeEnvironment.getApplication()
        TrackingRepository(context).clearHistory()
        val db = AppDatabase.get(context).writableDatabase
        val points = listOf(
            Triple(-0.0, 180.0, 1_000_000L), Triple(85.05112878, -179.999999, 1_120_000L),
            Triple(-90.0, -0.0, 1_150_000L), Triple(0.0000001, 90.0, 1_150_001L),
            Triple(12.34, -56.78, 1_180_002L), Triple(90.0, -180.0, 1_300_002L)
        )
        for ((lat, lon, time) in points) db.execSQL(
            "INSERT INTO track_points(latitude,longitude,accuracy_m,speed_mps,bearing_deg,timestamp_ms) VALUES (?,?,3,8,90,?)", arrayOf(lat, lon, time))
        val history = TripHistoryRepository(context)
        val trip = history.recentTrips().single()
        VipEntitlement.updateFromPlay(context, true)
        try {
            for (private in listOf(false, true)) {
                val output = ByteArrayOutputStream()
                val padding = if (private) 120_000L else 0L
                val included = points.filter { it.third in (trip.startedAt + padding)..(trip.endedAt - padding) }
                assertEquals(included.size, history.exportGpx(trip, output, private))
                val expected = buildString {
                    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                    append("<gpx version=\"1.1\" creator=\"Road Conquest\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n<trk><name>Road Conquest drive</name>\n")
                    var previous: Long? = null
                    for ((lat, lon, time) in included) {
                        if (previous == null || time <= previous || time - previous > 30_000L) {
                            if (previous != null) append("</trkseg>\n")
                            append("<trkseg>\n")
                        }
                        // SQLite normalizes negative zero when persisting a REAL.
                        val storedLat = if (lat == 0.0) 0.0 else lat
                        val storedLon = if (lon == 0.0) 0.0 else lon
                        append("<trkpt lat=\"$storedLat\" lon=\"$storedLon\"><time>${DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(time))}</time></trkpt>\n")
                        previous = time
                    }
                    if (previous != null) append("</trkseg>\n")
                    append("</trk></gpx>\n")
                }
                assertArrayEquals(expected.toByteArray(Charsets.UTF_8), output.toByteArray())
            }
        } finally { VipEntitlement.updateFromPlay(context, false) }
    }

    @Test fun allocationFixtureForActualTenThousandPointExport() {
        val context = RuntimeEnvironment.getApplication()
        TrackingRepository(context).clearHistory()
        val db = AppDatabase.get(context).writableDatabase
        db.execSQL("""WITH RECURSIVE n(x) AS (SELECT 0 UNION ALL SELECT x+1 FROM n WHERE x<9999)
            INSERT INTO track_points(latitude,longitude,accuracy_m,speed_mps,bearing_deg,timestamp_ms)
            SELECT 40.123456789,-74.123456789,3,8,90,1000000+x*1000 FROM n""")
        val history = TripHistoryRepository(context)
        val trip = history.recentTrips().single()
        VipEntitlement.updateFromPlay(context, true)
        try {
            val allocation = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            allocation.isThreadAllocatedMemoryEnabled = true
            fun export(): ByteArray {
                val output = ByteArrayOutputStream()
                assertEquals(10000, history.exportGpx(trip, output, false))
                return output.toByteArray()
            }
            repeat(3) { export() }
            val rounds = ArrayList<Long>()
            var signature = ""
            repeat(3) {
                val thread = Thread.currentThread().id
                val before = allocation.getThreadAllocatedBytes(thread)
                val bytes = export()
                rounds += allocation.getThreadAllocatedBytes(thread) - before
                signature = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            }
            println("GPX_ALLOCATION rounds=$rounds sha256=$signature points=10000")
        } finally { VipEntitlement.updateFromPlay(context, false) }
    }
}
