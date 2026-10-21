package com.roadconquest.app.data

import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class RoadIdentityFormattingTest {
    private fun previousKey(points: JSONArray): String {
        val rounded = List(points.length()) { i ->
            val p = points.getJSONArray(i)
            "%.5f,%.5f".format(Locale.US, p.getDouble(0), p.getDouble(1))
        }
        return minOf(rounded.joinToString(";"), rounded.asReversed().joinToString(";"))
    }
    private fun currentKey(repository: TrackingRepository, points: JSONArray): String =
        repository.javaClass.getDeclaredMethod("canonicalGeometryKey", JSONArray::class.java)
            .apply { isAccessible = true }.invoke(repository, points) as String

    @Test fun exactIdentityTextSurvivesSignedZeroRoundingBoundariesAndReversedRoutes() {
        val repository = TrackingRepository(RuntimeEnvironment.getApplication())
        val values = listOf(-180.0, 180.0, -90.0, 90.0, -0.0, 0.0,
            -0.000005, 0.000005, Math.nextUp(0.000005), Math.nextDown(0.000005),
            -179.9999999, 179.9999999, -0.0000001, 0.0000001, 89.9999999)
        for (size in listOf(0, 1, 2, values.size, values.size * 2)) {
            val points = JSONArray()
            repeat(size) { i -> points.put(JSONArray().put(values[i % values.size]).put(values[(i+3) % values.size].coerceIn(-90.0,90.0))) }
            val reverse = JSONArray()
            for (i in points.length()-1 downTo 0) reverse.put(points.getJSONArray(i))
            assertEquals(previousKey(points), currentKey(repository, points))
            assertEquals(previousKey(reverse), currentKey(repository, reverse))
            assertEquals(currentKey(repository, points), currentKey(repository, reverse))
        }
    }
    @Test fun seededTracesKeepTheExactPreviousIdentityUnderOtherDefaultLocales() {
        val repository = TrackingRepository(RuntimeEnvironment.getApplication())
        val original = Locale.getDefault()
        try {
            for (locale in listOf(Locale.GERMANY, Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("tr-TR"))) {
                Locale.setDefault(locale)
                val random = Random(59442)
                repeat(100) { n ->
                    val points = JSONArray()
                    repeat(2 + n % 17) { points.put(JSONArray().put(random.nextDouble()*360-180).put(random.nextDouble()*180-90)) }
                    assertEquals(previousKey(points), currentKey(repository, points))
                }
            }
        } finally { Locale.setDefault(original) }
    }
    @Test fun persistedCollisionIdsRemainExactlyCompatibleWithTheOldCanonicalText() {
        val context = RuntimeEnvironment.getApplication()
        TrackingRepository(context).clearHistory()
        val repository = TrackingRepository(context)
        val first = "[[-74,40],[-73.9995,40.0005],[-74.001,40]]"
        val second = "[[-74,40],[-74.0005,39.9995],[-74.001,40]]"
        val name = "Loop Road"
        repository.upsertRoads(listOf(MatchedRoad(name, first, 100, 200, 1.0), MatchedRoad(name, second, 300, 400, 1.0)))
        val expected = "g" + MessageDigest.getInstance("SHA-256")
            .digest(("loop road|"+previousKey(JSONArray(second))).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.US, it.toInt() and 255) }.take(31)
        val before = repository.getRoadsInBounds(41.0,-73.0,39.0,-75.0)
        assertEquals(2, before.size)
        assertEquals(expected, before.single { it.geometryJson==second }.segmentId)
        val reverse = JSONArray()
        val points = JSONArray(second)
        for (i in points.length()-1 downTo 0) reverse.put(points.getJSONArray(i))
        repository.upsertRoads(listOf(MatchedRoad(name, reverse.toString(), 500, 600, 1.0)))
        val after = repository.getRoadsInBounds(41.0,-73.0,39.0,-75.0)
        assertEquals(before.map { it.segmentId }.toSet(), after.map { it.segmentId }.toSet())
    }
}
