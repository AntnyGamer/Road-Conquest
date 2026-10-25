package com.roadconquest.app

import com.roadconquest.app.util.MeasurementUnits
import com.roadconquest.app.util.StatsText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class MeasurementUnitsTest {
    @Test fun regionalDataSelectsMetricCountriesAndPreservesUsAndBritishRoadUnits() {
        for (region in listOf("CA", "AU", "NZ", "FR", "DE", "JP", "IN", "BR")) {
            assertTrue(region, MeasurementUnits(Locale.forLanguageTag("en-$region")).metric)
        }
        for (region in listOf("US", "GB")) {
            val units = MeasurementUnits(Locale.forLanguageTag("en-$region"))
            assertFalse(region, units.metric)
            assertEquals("1.0 mi", units.longDistance(1609.344))
            assertEquals("100 ft", units.distance(30.48))
            assertEquals("1.0 sq mi", units.area(2.589988110336))
        }
    }

    @Test fun metricLabelsConvertTheSameDistancesAreasAndAchievementTargets() {
        val units = MeasurementUnits(Locale.CANADA)
        assertEquals("1.6 km", units.longDistance(1609.344))
        assertEquals("50 m", units.distance(50.0))
        assertEquals("1.0 km", units.distance(1000.0))
        assertEquals("2.6 km²", units.area(2.589988110336))
        assertEquals("8.0 / 16.1 km", units.distanceProgress(5 * 1609.344, 10 * 1609.344))
        assertEquals("1.6 km", units.tileSpan)
        val german = MeasurementUnits(Locale.GERMANY)
        assertEquals("1,6 km", german.longDistance(1609.344))
        assertEquals("1,6 km", german.tileSpan)
    }

    @Test fun cachedSnapshotChangesWithLocaleAndShortUsDrivesNeverRoundToZeroMiles() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val us = MeasurementUnits.current()
            assertSame(us, MeasurementUnits.current())
            assertEquals("164 ft", us.distance(50.0))
            assertEquals("Distance not recorded", StatsText.tripDistance(0.0))
            Locale.setDefault(Locale.CANADA)
            val metric = MeasurementUnits.current()
            assertNotSame(us, metric)
            assertEquals("50 m", metric.distance(50.0))
        } finally { Locale.setDefault(original) }
    }
}
