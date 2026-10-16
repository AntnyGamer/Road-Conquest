package com.roadconquest.app.data

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class TileDiscoveryMigrationTest {
    @Test fun upgradingLegacyGridRetainsCellsWithUnknownFirstUnlockTimes() {
        val helper = AppDatabase.get(RuntimeEnvironment.getApplication())
        SQLiteDatabase.create(null).use { db ->
            db.execSQL(
                "CREATE TABLE explored_grid (" +
                    "grid_row INTEGER NOT NULL, grid_col INTEGER NOT NULL, " +
                    "latitude REAL NOT NULL, longitude REAL NOT NULL, " +
                    "PRIMARY KEY (grid_row,grid_col))"
            )
            db.execSQL("INSERT INTO explored_grid VALUES (123,456,40.0,-74.0)")
            helper.onUpgrade(db, 13, 14)
            db.rawQuery("SELECT grid_row, grid_col, first_unlocked_at FROM explored_grid", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(123, it.getInt(0))
                assertEquals(456, it.getInt(1))
                assertEquals(0L, it.getLong(2))
            }
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='index' AND name='idx_explored_grid_first_unlocked'", null).use {
                assertTrue(it.moveToFirst())
            }
        }
    }
}
