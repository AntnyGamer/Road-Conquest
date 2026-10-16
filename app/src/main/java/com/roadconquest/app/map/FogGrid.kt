package com.roadconquest.app.map

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Globally stable, north-aligned exploration squares. Latitude rows and longitude
 * columns share fixed boundaries: adjacent visited cells form one seamless region.
 *
 * At the app's primary mid-latitudes (around 40 degrees N/S) each side is one
 * statute mile on the ground. Longitude width varies physically with latitude
 * because a single aligned rectangular grid cannot be exactly square everywhere
 * on a sphere. Unlike latitude-dependent columns, these cells never create T
 * junctions or gaps at a row boundary.
 */
internal object FogGrid {
    const val VERSION = "fog-grid-v1"
    private const val MILE_METERS = 1609.344
    private const val METERS_PER_LATITUDE_DEGREE = 111_195.0
    private const val LATITUDE_STEP = MILE_METERS / METERS_PER_LATITUDE_DEGREE
    private const val REFERENCE_LATITUDE = 40.0
    private const val MAX_MERCATOR_LATITUDE = 85.05112878
    private val columnCount = (360.0 * METERS_PER_LATITUDE_DEGREE *
        cos(Math.toRadians(REFERENCE_LATITUDE)) / MILE_METERS).roundToInt()
    private val longitudeStep = 360.0 / columnCount

    data class Cell(val row: Int, val column: Int) {
        val key: Long get() = (row.toLong() shl 32) or (column.toLong() and 0xffff_ffffL)
    }

    fun cell(latitude: Double, longitude: Double): Cell? {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0
        ) return null
        val row = floor((latitude.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE) +
            90.0) / LATITUDE_STEP).toInt()
        val wrapped = ((longitude + 180.0) % 360.0 + 360.0) % 360.0
        return Cell(row, floor(wrapped / longitudeStep).toInt().coerceIn(0, columnCount - 1))
    }

    /** Nearby tile goals use the same globally aligned grid, including date-line wrapping. */
    fun neighbors(cell: Cell, radius: Int = 2): List<Cell> {
        require(radius in 0..5)
        val firstRow = floor((90.0 - MAX_MERCATOR_LATITUDE) / LATITUDE_STEP).toInt()
        val lastRow = floor((90.0 + MAX_MERCATOR_LATITUDE) / LATITUDE_STEP).toInt()
        val output = ArrayList<Cell>((2 * radius + 1) * (2 * radius + 1))
        for (row in (cell.row - radius)..(cell.row + radius)) {
            if (row !in firstRow..lastRow) continue
            for (offset in -radius..radius) {
                val column = ((cell.column + offset) % columnCount + columnCount) % columnCount
                output += Cell(row, column)
            }
        }
        return output
    }

    fun center(cell: Cell): Pair<Double, Double> {
        val south = (-90.0 + cell.row * LATITUDE_STEP).coerceAtLeast(-MAX_MERCATOR_LATITUDE)
        val north = (-90.0 + (cell.row + 1) * LATITUDE_STEP).coerceAtMost(MAX_MERCATOR_LATITUDE)
        return (south + north) / 2.0 to (-180.0 + (cell.column + 0.5) * longitudeStep)
    }

    /** Four clockwise corners: NW, NE, SE, SW as latitude/longitude pairs. */
    fun corners(latitude: Double, longitude: Double): DoubleArray {
        val tile = cell(latitude, longitude) ?: return doubleArrayOf()
        return DoubleArray(8).also { writeCorners(tile, it, 0) }
    }

    fun visitedCorners(samples: DoubleArray): DoubleArray {
        val unique = HashSet<Long>()
        val cells = ArrayList<Cell>()
        for (i in 0 until samples.size - 1 step 2) {
            val tile = cell(samples[i], samples[i + 1]) ?: continue
            if (unique.add(tile.key)) cells.add(tile)
        }
        return DoubleArray(cells.size * 8).also { output ->
            cells.forEachIndexed { index, tile -> writeCorners(tile, output, index * 8) }
        }
    }

    /**
     * Explored-grid centers fetched from the database are unique by its
     * (grid_row, grid_col) PRIMARY KEY. Skip a redundant HashSet and Cell list
     * when turning that already-deduplicated query into renderable squares.
     * Other callers can continue using visitedCorners(), which deduplicates.
     */
    fun uniqueVisitedCorners(samples: DoubleArray): DoubleArray {
        val output = DoubleArray(samples.size * 4)
        var offset = 0
        for (i in 0 until samples.size - 1 step 2) {
            val tile = cell(samples[i], samples[i + 1]) ?: continue
            writeCorners(tile, output, offset)
            offset += 8
        }
        return if (offset == output.size) output else output.copyOf(offset)
    }

    /** Project date-line cells into the same copy of the world as the camera. */
    fun nearLongitude(corners: DoubleArray, centerLongitude: Double): DoubleArray {
        if (corners.isEmpty() || !centerLongitude.isFinite()) return corners
        val shifted = corners.copyOf()
        for (i in 0 until shifted.size - 7 step 8) {
            val longitude = (shifted[i + 1] + shifted[i + 3]) / 2.0
            val offset = floor((centerLongitude - longitude) / 360.0 + 0.5) * 360.0
            if (offset == 0.0) continue
            for (corner in 0..3) shifted[i + 2 * corner + 1] += offset
        }
        return shifted
    }

    private fun writeCorners(cell: Cell, output: DoubleArray, i: Int) {
        val north = (-90.0 + (cell.row + 1) * LATITUDE_STEP).coerceAtMost(MAX_MERCATOR_LATITUDE)
        val south = (-90.0 + cell.row * LATITUDE_STEP).coerceAtLeast(-MAX_MERCATOR_LATITUDE)
        val west = -180.0 + cell.column * longitudeStep
        val east = west + longitudeStep
        output[i] = north
        output[i + 1] = west
        output[i + 2] = north
        output[i + 3] = east
        output[i + 4] = south
        output[i + 5] = east
        output[i + 6] = south
        output[i + 7] = west
    }
}
