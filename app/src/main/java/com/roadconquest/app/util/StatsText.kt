package com.roadconquest.app.util

import android.content.Context
import com.roadconquest.app.R
import com.roadconquest.app.data.DataSummary
import com.roadconquest.app.data.ProgressionSnapshot
import java.util.Locale

object StatsText {
    /** Short genuine drives should not round down to a misleading 0.0 miles. */
    fun tripDistance(meters: Double): String = MeasurementUnits.current().distance(meters)

    /** Two compact, clearly labeled categories on top of the map. */
    fun mapOverview(context: Context, summary: DataSummary, tiles: Long): String {
        val roads = context.resources.getQuantityString(
            R.plurals.roads_short, summary.roadsUnlockedCount.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            summary.roadsUnlockedCount
        )
        val tileLabel = context.resources.getQuantityString(
            R.plurals.tiles_short, tiles.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), tiles
        )
        return context.getString(R.string.map_stats_grouped, MeasurementUnits.current().longDistance(summary.distanceMeters),
            roads, tileLabel)
    }

    /** Full categorized breakdown with no new database or network work. */
    fun settingsOverview(context: Context, summary: DataSummary, progress: ProgressionSnapshot): String =
        buildString {
            append("DRIVING\n")
            append(format(context, summary))
            append("\n\nEXPLORATION\n")
            append(String.format(Locale.getDefault(), "%,d map tiles • %,d towns\n%,d states/regions • %,d countries",
                progress.tilesUnlocked, progress.towns, progress.states, progress.countries))
            append("\n\nPROGRESSION\n")
            append(String.format(Locale.getDefault(), "⚔ %,d points", progress.balance))
        }

    fun format(context: Context, summary: DataSummary): String {
        val roads = context.resources.getQuantityString(R.plurals.roads_unlocked,
            summary.roadsUnlockedCount.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), summary.roadsUnlockedCount)
        return context.getString(R.string.map_stats, MeasurementUnits.current().longDistance(summary.distanceMeters), roads)
    }
}
