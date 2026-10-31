package com.roadconquest.app.util

import android.icu.util.LocaleData
import android.icu.util.ULocale
import java.util.Locale

enum class DistanceUnitPreference(val label: String) {
    AUTOMATIC("Automatic (phone setting)"), MILES("Miles"), KILOMETERS("Kilometers")
}

/** Display conversions only. Reuse a snapshot until the phone locale or unit choice changes. */
class MeasurementUnits(val locale: Locale, val preference: DistanceUnitPreference = DistanceUnitPreference.AUTOMATIC) {
    val metric = when (preference) {
        DistanceUnitPreference.AUTOMATIC ->
            LocaleData.getMeasurementSystem(ULocale.forLocale(locale)) == LocaleData.MeasurementSystem.SI
        DistanceUnitPreference.MILES -> false
        DistanceUnitPreference.KILOMETERS -> true
    }
    private val distanceDivisor = if (metric) 1000.0 else METERS_PER_MILE
    private val smallDistanceDivisor = if (metric) 1.0 else 0.3048
    private val smallDistanceLimit = if (metric) 1000.0 else 160.9344
    private val smallDistanceUnit = if (metric) "m" else "ft"
    val distanceUnit = if (metric) "km" else "mi"
    val tileSpan = if (metric) String.format(locale, "%.1f km", METERS_PER_MILE / 1000) else "one-mile"

    fun longDistance(meters: Double, decimals: Int = 1): String =
        String.format(locale, "%,.${decimals}f %s", meters / distanceDivisor, distanceUnit)

    fun distance(meters: Double, decimals: Int = 1): String =
        if (meters < smallDistanceLimit) {
            String.format(locale, "%.0f %s", meters.coerceAtLeast(0.0) / smallDistanceDivisor, smallDistanceUnit)
        } else longDistance(meters, decimals)

    fun area(squareKilometers: Double): String {
        val value = if (metric) squareKilometers else squareKilometers / 2.589988110336
        return String.format(locale, if (value >= 1.0) "%,.1f %s" else "%,.2f %s", value,
            if (metric) "km²" else "sq mi")
    }

    fun distanceProgress(progressMeters: Double, goalMeters: Double): String =
        String.format(locale, if (metric) "%,.1f / %,.1f %s" else "%,.1f / %,.0f %s",
            progressMeters.coerceAtMost(goalMeters) / distanceDivisor, goalMeters / distanceDivisor, distanceUnit)

    companion object {
        const val METERS_PER_MILE = 1609.344
        @Volatile private var cached: MeasurementUnits? = null
        @Volatile private var preference = DistanceUnitPreference.AUTOMATIC
        fun configure(value: DistanceUnitPreference) { preference = value }
        fun current(): MeasurementUnits {
            val locale = Locale.getDefault()
            val selected = preference
            return cached?.takeIf { it.locale == locale && it.preference == selected }
                ?: MeasurementUnits(locale, selected).also { cached = it }
        }
    }
}
