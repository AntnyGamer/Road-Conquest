package com.roadconquest.app.data

import android.location.Location
import com.roadconquest.app.util.Prefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class DeletedStorageTest {
    @Test fun explicitDeletionReclaimsEmptyPagesAndTheDatabaseStillAcceptsNewHistory() {
        val app = RuntimeEnvironment.getApplication()
        val repo = TrackingRepository(app)
        repo.clearHistory()
        val db = repo.readableDatabase()
        db.execSQL("""WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<20000)
            INSERT INTO explored_places(cell_x,cell_y,latitude,longitude) SELECT x,x,40,-74 FROM n""")
        fun pages(pragma: String) = db.rawQuery("PRAGMA $pragma", null).use { check(it.moveToFirst()); it.getLong(0) }
        val populatedPages = pages("page_count")
        val staleOwner = TrackingRepository(app)
        LocalDataReset.stopTracking(app)
        try {
            LocalDataReset.clearStoppedData(app)
            assertFalse(Prefs.isDeviceDataDeletionPending(app))
            assertFalse(staleOwner.isHistoryCurrent())
            val compactedPages = pages("page_count")
            assertTrue("Deletion must reclaim allocated DB pages: $populatedPages -> $compactedPages",
                compactedPages < populatedPages / 2)
            assertEquals(0L, pages("freelist_count"))
            val fresh = TrackingRepository(app)
            assertEquals(0L, fresh.getSummary().trackPointCount)
            assertTrue(fresh.insertLocation(Location("gps").apply {
                latitude = 40.0; longitude = -74.0; accuracy = 3f; speed = 8f; time = 1000L
            }) > 0)
            assertEquals(1L, fresh.getSummary().trackPointCount)
            assertTrue(Prefs.isManualOnly(app))
        } finally {
            Prefs.setDeviceDataDeletionPending(app, false)
            TrackingRepository(app).clearHistory()
        }
    }
}
