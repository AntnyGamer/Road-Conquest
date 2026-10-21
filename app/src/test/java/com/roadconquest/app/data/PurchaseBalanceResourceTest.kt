package com.roadconquest.app.data

import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class PurchaseBalanceResourceTest {
    @Test fun purchaseChecksBothTotalsWithOneCursorAndKeepsOwnershipAndBalanceRules() {
        val context = RuntimeEnvironment.getApplication()
        val repository = ProgressionRepository(context)
        repository.clearProgression()
        val db = AppDatabase.get(context).writableDatabase
        val field = SQLiteDatabase::class.java.getDeclaredField("mCursorFactory").apply { isAccessible = true }
        val previous = field.get(db)
        var totalQueries = 0
        field.set(db, SQLiteDatabase.CursorFactory { _, driver, table, query ->
            if (query.toString().contains("SUM(points)") || query.toString().contains("SUM(points_spent)")) totalQueries++
            SQLiteCursor(driver, table, query)
        })
        try {
            fun check(expected: PurchaseResult, id: String, cost: Long, queries: Int = 1) {
                totalQueries = 0
                assertEquals(expected, repository.purchase(id, cost))
                assertEquals("balance cursors for $id", queries, totalQueries)
            }
            check(PurchaseResult.INSUFFICIENT_POINTS, "costs-one", 1)
            check(PurchaseResult.PURCHASED, "free", 0)
            check(PurchaseResult.OWNED, "free", 99, 0)
            assertTrue(repository.awardAchievement("fixture", 100))
            check(PurchaseResult.PURCHASED, "ninety", 90)
            check(PurchaseResult.INSUFFICIENT_POINTS, "eleven", 11)
            check(PurchaseResult.PURCHASED, "ten", 10)
            check(PurchaseResult.OWNED, "ten", 999, 0)
            assertEquals(0L, repository.snapshot().balance)
            assertEquals(100L, repository.snapshot().pointsSpent)
        } finally { field.set(db, previous) }
    }

    @Test fun balancePreservesLongArithmeticAndIndividualSqlSumOverflow() {
        val context = RuntimeEnvironment.getApplication()
        val repository = ProgressionRepository(context)
        repository.clearProgression()
        val db = AppDatabase.get(context).writableDatabase
        val method = ProgressionRepository::class.java.getDeclaredMethod("currentBalance", SQLiteDatabase::class.java)
            .apply { isAccessible = true }
        for ((earned, spent) in listOf(0L to 0L, 100L to 20L, 20L to 100L,
            Long.MAX_VALUE to 0L, 1L to Long.MAX_VALUE)) {
            db.delete("progression_rewards", null, null)
            db.delete("progression_purchases", null, null)
            if (earned > 0) db.execSQL("INSERT INTO progression_rewards VALUES ('fixture',?,1)", arrayOf(earned))
            db.execSQL("INSERT INTO progression_purchases VALUES ('fixture',?,1)", arrayOf(spent))
            assertEquals(earned - spent, method.invoke(repository, db))
        }
        db.execSQL("INSERT INTO progression_rewards VALUES ('overflow',?,1)", arrayOf(Long.MAX_VALUE))
        val failure = assertThrows(java.lang.reflect.InvocationTargetException::class.java) { method.invoke(repository, db) }
        assertTrue(failure.cause is android.database.sqlite.SQLiteException)
    }
}
