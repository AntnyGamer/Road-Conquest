package com.roadconquest.app.map

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.roadconquest.app.data.RoadRecord
import kotlin.math.*

/** Parsed off the UI thread. Native batch projection accepts latitude, longitude pairs. */
class OverlayRoads(val coordinates: DoubleArray, val starts: IntArray) {
    companion object {
        val EMPTY = OverlayRoads(doubleArrayOf(), intArrayOf(0))

        fun prepare(roads: List<RoadRecord>, visitWindows: Map<String, List<Pair<Long, Long>>> = emptyMap()): OverlayRoads {
            if (roads.isEmpty()) return EMPTY
            val segments = ArrayList<Segment>(roads.size)
            val segmentFirstTimes = ArrayList<Long>(roads.size)
            val segmentLastTimes = ArrayList<Long>(roads.size)
            val segmentVisits = if (visitWindows.isEmpty()) null else ArrayList<List<Pair<Long, Long>>>(roads.size)
            val nodes = ArrayList<Node>(roads.size * 2)
            val buckets = HashMap<Cell, MutableList<Int>>(roads.size * 2)
            val lookupCell = Cell(0, 0, 0)

            fun assignNode(latitude: Double, longitude: Double, excludedNode: Int = -1): Int {
                val lat = Math.toRadians(latitude)
                val lon = Math.toRadians(longitude)
                val cosLat = cos(lat)
                val cellX = floor(EARTH_RADIUS_M * cosLat * cos(lon) / ENDPOINT_JOIN_TOLERANCE_M).toLong()
                val cellY = floor(EARTH_RADIUS_M * cosLat * sin(lon) / ENDPOINT_JOIN_TOLERANCE_M).toLong()
                val cellZ = floor(EARTH_RADIUS_M * sin(lat) / ENDPOINT_JOIN_TOLERANCE_M).toLong()
                var best = -1
                var bestDistance = Double.POSITIVE_INFINITY
                for (dx in -1L..1L) for (dy in -1L..1L) for (dz in -1L..1L) {
                    lookupCell.set(cellX + dx, cellY + dy, cellZ + dz)
                    for (candidate in buckets[lookupCell].orEmpty()) {
                        if (candidate == excludedNode) continue
                        val node = nodes[candidate]
                        val distance = metersBetween(latitude, longitude, node.latitude, node.longitude)
                        if (distance <= ENDPOINT_JOIN_TOLERANCE_M && distance < bestDistance) {
                            best = candidate
                            bestDistance = distance
                        }
                    }
                }
                if (best >= 0) return best
                val index = nodes.size
                nodes += Node(latitude, longitude)
                buckets.getOrPut(Cell(cellX, cellY, cellZ)) { ArrayList(2) } += index
                return index
            }

            fun addSegment(coordinates: DoubleArray, firstUnlockedAt: Long, lastDrivenAt: Long,
                           visits: List<Pair<Long, Long>>) {
                if (coordinates.size < 4) return
                val startLatitude = coordinates[0]
                val startLongitude = coordinates[1]
                val endLatitude = coordinates[coordinates.size - 2]
                val endLongitude = coordinates[coordinates.size - 1]
                val startNode = assignNode(startLatitude, startLongitude)
                val endNode = if (
                    coordinates.size > 4 &&
                    metersBetween(startLatitude, startLongitude, endLatitude, endLongitude) <= DUPLICATE_ENDPOINT_M
                ) {
                    startNode
                } else {
                    assignNode(endLatitude, endLongitude, startNode)
                }
                segments += Segment(coordinates, startNode, endNode)
                segmentFirstTimes += firstUnlockedAt
                segmentLastTimes += lastDrivenAt
                segmentVisits?.add(visits)
            }

            for (road in roads) {
                val points = GeoJsonUtil.validRoadCoordinates(road.geometryJson) ?: continue
                val visits = visitWindows[road.segmentId].orEmpty()
                val part = DoubleBuffer(points.length() * 2 + 4)
                var previous = points.getJSONArray(0)
                part.add(previous.getDouble(1))
                part.add(previous.getDouble(0))
                for (i in 1 until points.length()) {
                    val current = points.getJSONArray(i)
                    val previousLongitude = previous.getDouble(0)
                    val currentLongitude = current.getDouble(0)
                    val currentLatitude = current.getDouble(1)
                    val delta = currentLongitude - previousLongitude
                    if (abs(delta) > 180.0) {
                        // GeoJSON itself is wrapped to [-180, 180]. Split a real date-line
                        // crossing into two short pieces so neither MapLibre nor the fog
                        // projector can interpret it as a line across the rest of the world.
                        val unwrappedCurrent = currentLongitude - 360.0 * sign(delta)
                        val boundary = if (delta < 0.0) 180.0 else -180.0
                        val fraction = (boundary - previousLongitude) /
                            (unwrappedCurrent - previousLongitude)
                        val crossingLatitude = previous.getDouble(1) +
                            (currentLatitude - previous.getDouble(1)) * fraction
                        part.add(crossingLatitude)
                        part.add(boundary)
                        addSegment(part.toArray(), road.firstUnlockedAt, road.lastDrivenAt, visits)
                        part.clear()
                        part.add(crossingLatitude)
                        part.add(-boundary)
                    }
                    part.add(currentLatitude)
                    part.add(currentLongitude)
                    previous = current
                }
                addSegment(part.toArray(), road.firstUnlockedAt, road.lastDrivenAt, visits)
            }
            if (segments.isEmpty()) return EMPTY

            val degree = IntArray(nodes.size)
            val firstAdjacent = IntArray(nodes.size) { -1 }
            val secondAdjacent = IntArray(nodes.size) { -1 }
            fun addAdjacent(node: Int, segment: Int) {
                if (degree[node] == 0) firstAdjacent[node] = segment
                else if (degree[node] == 1) secondAdjacent[node] = segment
                degree[node]++
            }
            for (i in segments.indices) {
                addAdjacent(segments[i].startNode, i)
                addAdjacent(segments[i].endNode, i)
            }

            // Repair only confirmed, dangling road ends recorded seconds apart on the same
            // drive. These are display-only links: no roads, miles or achievements are awarded.
            // Snap to the intersection of the two already-matched centerline directions;
            // never draw a long straight chord that cuts through the inside of a turn.
            val originalSegmentCount = segments.size
            // Protect the original first-unlock connections before considering other visits.
            // Mixing later visits into this pass could make a previously valid turn ambiguous.
            appendSupportedJunctions(segments, segmentFirstTimes, segmentLastTimes, nodes, degree)
            val occupied = HashSet<Int>()
            fun reserveConnectors(since: Int) {
                for (index in since until segments.size) {
                    occupied += segments[index].startNode
                    occupied += segments[index].endNode
                }
            }
            reserveConnectors(originalSegmentCount)
            if (segmentVisits != null) {
                // Saved visit intervals are stronger evidence than aggregate last-driven times.
                // Add only unoccupied endpoints; never displace the original visible turns.
                appendSupportedJunctions(segments, segmentFirstTimes, segmentLastTimes, nodes,
                    degree, segmentVisits, occupied)
            } else {
                // On larger viewports or if visit retrieval fails, keep the later-drive
                // fallback without allowing it to erase first-unlock connections.
                appendSupportedJunctions(segments, segmentFirstTimes, segmentLastTimes, nodes,
                    degree, occupiedNodes = occupied, includeLastTimes = true)
            }
            // Give each synthetic segment the same graph-adjacency treatment as actual roads.
            for (index in originalSegmentCount until segments.size) {
                addAdjacent(segments[index].startNode, index)
                addAdjacent(segments[index].endNode, index)
            }

            val output = DoubleBuffer((segments.sumOf { it.coordinates.size }).coerceAtLeast(16))
            val starts = IntArray(segments.size + 1)
            val used = BooleanArray(segments.size)
            var chainCount = 0

            fun walk(first: Int, startNode: Int) {
                var segmentIndex = first
                var nodeIndex = startNode
                var firstSegment = true
                while (!used[segmentIndex]) {
                    val segment = segments[segmentIndex]
                    val forward = segment.startNode == nodeIndex
                    appendSegment(output, segment, forward, nodes, joinPrevious = !firstSegment)
                    firstSegment = false
                    used[segmentIndex] = true
                    nodeIndex = if (forward) segment.endNode else segment.startNode
                    if (degree[nodeIndex] != 2) break
                    val next = if (firstAdjacent[nodeIndex] != segmentIndex) {
                        firstAdjacent[nodeIndex]
                    } else {
                        secondAdjacent[nodeIndex]
                    }
                    if (next < 0 || used[next]) break
                    segmentIndex = next
                }
                starts[++chainCount] = output.size
            }

            // Stop chains at real junctions/endpoints. All branches still snap to the same
            // sub-meter node, so turns remain visually connected without inventing a shortcut.
            for (i in segments.indices) {
                if (used[i]) continue
                val segment = segments[i]
                val startDegree = degree[segment.startNode]
                val endDegree = degree[segment.endNode]
                if (startDegree != 2 || endDegree != 2) {
                    walk(i, if (startDegree != 2) segment.startNode else segment.endNode)
                }
            }
            // Closed loops have degree two everywhere and need an arbitrary starting point.
            for (i in segments.indices) {
                if (!used[i]) walk(i, segments[i].startNode)
            }
            return OverlayRoads(
                output.finish(),
                if (chainCount + 1 == starts.size) starts else starts.copyOf(chainCount + 1)
            )
        }

        /**
         * The matcher can leave a short hole exactly where one named road becomes another.
         * Use both original unlock and last-driven timestamps: a road unlocked on an earlier
         * trip can still be the confirmed approach to a newly unlocked road today. Require
         * nearby times AND unique intersecting centerlines, never geometry alone.
         * This is strictly a visual repair; no local or verified road credit is created.
         */
        private fun appendSupportedJunctions(
            segments: MutableList<Segment>,
            firstTimes: List<Long>,
            lastTimes: List<Long>,
            nodes: List<Node>,
            degree: IntArray,
            visits: List<List<Pair<Long, Long>>>? = null,
            occupiedNodes: Set<Int> = emptySet(),
            includeLastTimes: Boolean = false
        ) {
            // Both passes inspect the original roads, never the already added connectors.
            val originalSize = firstTimes.size
            val startsBySecond = HashMap<Long, MutableList<Int>>()
            for (index in 0 until originalSize) {
                if (degree[segments[index].startNode] != 1 ||
                    segments[index].startNode in occupiedNodes) continue
                if (visits != null) {
                    for ((startedAt, _) in visits[index]) {
                        startsBySecond.getOrPut(startedAt / 1_000L) { ArrayList(2) } += index
                    }
                } else {
                    startsBySecond.getOrPut(firstTimes[index] / 1_000L) { ArrayList(2) } += index
                    if (includeLastTimes && lastTimes[index] != firstTimes[index]) {
                        startsBySecond.getOrPut(lastTimes[index] / 1_000L) { ArrayList(2) } += index
                    }
                }
            }
            val bestFrom = arrayOfNulls<JunctionCandidate>(originalSize)
            val bestTo = arrayOfNulls<JunctionCandidate>(originalSize)
            val secondFrom = DoubleArray(originalSize) { Double.POSITIVE_INFINITY }
            val secondTo = DoubleArray(originalSize) { Double.POSITIVE_INFINITY }
            // One segment pair can qualify at both original and recent timestamps.
            // It must count as ONE candidate, not an ambiguous second connection.
            val seenFrom = IntArray(originalSize) { -1 }

            fun offer(index: Int, candidate: JunctionCandidate, best: Array<JunctionCandidate?>, second: DoubleArray) {
                val previous = best[index]
                if (previous == null || candidate.distance < previous.distance) {
                    second[index] = previous?.distance ?: second[index]
                    best[index] = candidate
                } else if (candidate.distance < second[index]) {
                    second[index] = candidate.distance
                }
            }

            for (from in 0 until originalSize) {
                val left = segments[from]
                if (degree[left.endNode] != 1 || left.endNode in occupiedNodes) continue
                // Normal pass remains identical; the additional pass pairs the end of one
                // recorded visit with the beginning of another visit.
                for (timeIndex in 0 until (visits?.get(from)?.size ?: if (includeLastTimes) 2 else 1)) {
                    val time = if (visits == null) {
                        if (timeIndex == 0) firstTimes[from] else lastTimes[from]
                    } else visits[from][timeIndex].second
                    if (visits == null && timeIndex == 1 && time == firstTimes[from]) continue
                    // Bounded timestamp index: avoids quadratic work with years of unlocked roads.
                    for (second in time / 1_000L..time / 1_000L + 15L) {
                        for (to in startsBySecond[second].orEmpty()) {
                            if (to == from || seenFrom[to] == from) continue
                            val timeMatches = if (visits == null) {
                                val firstGap = firstTimes[to] - time
                                val lastGap = lastTimes[to] - time
                                firstGap in 1L..MAX_SUPPORTED_JUNCTION_TIME_MS ||
                                    (includeLastTimes && lastGap in 1L..MAX_SUPPORTED_JUNCTION_TIME_MS)
                            } else {
                                visits[to].any { (startedAt, _) ->
                                    startedAt - time in 1L..MAX_SUPPORTED_JUNCTION_TIME_MS
                                }
                            }
                            if (!timeMatches) continue
                            val right = segments[to]
                            val distance = metersBetween(
                                nodes[left.endNode].latitude, nodes[left.endNode].longitude,
                                nodes[right.startNode].latitude, nodes[right.startNode].longitude
                            )
                            if (distance !in ENDPOINT_JOIN_TOLERANCE_M..MAX_SUPPORTED_JUNCTION_DISTANCE_M) continue
                            val bend = supportedJunctionBend(left.coordinates, right.coordinates, distance) ?: continue
                            seenFrom[to] = from
                            val candidate = JunctionCandidate(from, to, distance, bend)
                            offer(from, candidate, bestFrom, secondFrom)
                            offer(to, candidate, bestTo, secondTo)
                        }
                    }
                }
            }

            for (from in 0 until originalSize) {
                val candidate = bestFrom[from] ?: continue
                if (bestTo[candidate.to] !== candidate ||
                    secondFrom[from] - candidate.distance < MIN_UNIQUE_JUNCTION_MARGIN_M ||
                    secondTo[candidate.to] - candidate.distance < MIN_UNIQUE_JUNCTION_MARGIN_M
                ) continue
                val left = segments[candidate.from]
                val right = segments[candidate.to]
                val end = nodes[left.endNode]
                val start = nodes[right.startNode]
                segments += Segment(
                    doubleArrayOf(
                        end.latitude, end.longitude, candidate.bendLatitude, candidate.bendLongitude,
                        start.latitude, start.longitude
                    ),
                    left.endNode,
                    right.startNode
                )
            }
        }

        private data class JunctionCandidate(
            val from: Int,
            val to: Int,
            val distance: Double,
            val bend: Pair<Double, Double>
        ) {
            val bendLatitude get() = bend.first
            val bendLongitude get() = bend.second
        }

        /** Find the closest sound approach/exit bearing; never extrapolate far down a road. */
        private fun junctionTangentIndex(coordinates: DoubleArray, atEnd: Boolean): Int? {
            val end = if (atEnd) coordinates.size - 2 else 0
            val step = if (atEnd) -2 else 2
            var previous = end
            var index = end + step
            var walked = 0.0
            while (index >= 0 && index + 1 < coordinates.size) {
                val edgeDistance = metersBetween(
                    coordinates[previous], coordinates[previous + 1],
                    coordinates[index], coordinates[index + 1]
                )
                walked += edgeDistance
                val endpointDistance = if (previous == end) edgeDistance else metersBetween(
                    coordinates[end], coordinates[end + 1],
                    coordinates[index], coordinates[index + 1]
                )
                if (endpointDistance >= MIN_JUNCTION_DIRECTION_M) {
                    // Preserve the previous behavior when the immediate edge is already long.
                    if (index == end + step || walked <= MAX_JUNCTION_TANGENT_LOOKBACK_M) return index
                    return null
                }
                if (walked > MAX_JUNCTION_TANGENT_LOOKBACK_M) return null
                previous = index
                index += step
            }
            return null
        }

        private fun supportedJunctionBend(
            left: DoubleArray, right: DoubleArray, directMeters: Double
        ): Pair<Double, Double>? {
            if (left.size < 4 || right.size < 4) return null
            val aLat = left[left.size - 2]
            val aLon = left.last()
            // OSRM can end an otherwise sound road with a sub-meter vertex. Use the
            // nearest sufficiently long tangent on that same confirmed polyline instead
            // of rejecting an ordinary turn because only its final micro-edge is short.
            val before = junctionTangentIndex(left, atEnd = true) ?: return null
            val after = junctionTangentIndex(right, atEnd = false) ?: return null
            val bLat = left[before]
            val bLon = left[before + 1]
            val cLat = right[0]
            val cLon = right[1]
            val dLat = right[after]
            val dLon = right[after + 1]
            val metersPerDegree = EARTH_RADIUS_M * PI / 180.0
            val metersPerLongitude = metersPerDegree * cos(Math.toRadians((aLat + cLat) / 2.0))
            if (metersPerLongitude <= 0.0) return null
            val ux = (aLon - bLon) * metersPerLongitude
            val uy = (aLat - bLat) * metersPerDegree
            val vx = (dLon - cLon) * metersPerLongitude
            val vy = (dLat - cLat) * metersPerDegree
            if (hypot(ux, uy) < MIN_JUNCTION_DIRECTION_M ||
                hypot(vx, vy) < MIN_JUNCTION_DIRECTION_M) return null
            val wx = (cLon - aLon) * metersPerLongitude
            val wy = (cLat - aLat) * metersPerDegree
            val divisor = ux * vy - uy * vx
            if (abs(divisor) < 0.01) return null
            val approach = (wx * vy - wy * vx) / divisor
            val exit = (wx * uy - wy * ux) / divisor
            // The two centerline rays must actually meet ahead of the approach and behind
            // the exit. Reject parallel roads, opposing traffic and fabricated shortcuts.
            if (approach < 0.0 || exit > 0.0) return null
            val toBend = hypot(approach * ux, approach * uy)
            val fromBend = hypot(exit * vx, exit * vy)
            if (toBend > MAX_SUPPORTED_JUNCTION_DISTANCE_M ||
                fromBend > MAX_SUPPORTED_JUNCTION_DISTANCE_M ||
                toBend + fromBend > directMeters * 1.5 + 8.0) return null
            return (aLat + approach * uy / metersPerDegree) to
                (aLon + approach * ux / metersPerLongitude)
        }

        private fun appendSegment(
            output: DoubleBuffer,
            segment: Segment,
            forward: Boolean,
            nodes: List<Node>,
            joinPrevious: Boolean
        ) {
            val coordinates = segment.coordinates
            val pointCount = coordinates.size / 2
            val firstNode = nodes[if (forward) segment.startNode else segment.endNode]
            val lastNode = nodes[if (forward) segment.endNode else segment.startNode]
            appendPoint(output, firstNode.latitude, firstNode.longitude, joinPrevious)

            if (forward) {
                for (point in 1 until pointCount - 1) {
                    appendPoint(output, coordinates[point * 2], coordinates[point * 2 + 1], true)
                }
            } else {
                for (point in pointCount - 2 downTo 1) {
                    appendPoint(output, coordinates[point * 2], coordinates[point * 2 + 1], true)
                }
            }
            appendPoint(output, lastNode.latitude, lastNode.longitude, true)
        }

        private fun appendPoint(
            output: DoubleBuffer,
            latitude: Double,
            longitude: Double,
            deduplicatePrevious: Boolean
        ) {
            if (deduplicatePrevious && output.size >= 2 &&
                output[output.size - 2] == latitude &&
                output[output.size - 1] == longitude
            ) return
            output.add(latitude)
            output.add(longitude)
        }

        private data class Segment(
            val coordinates: DoubleArray,
            val startNode: Int,
            val endNode: Int
        )

        private class Node(val latitude: Double, val longitude: Double)

        private class Cell(var x: Long, var y: Long, var z: Long) {
            fun set(x: Long, y: Long, z: Long) {
                this.x = x
                this.y = y
                this.z = z
            }

            override fun equals(other: Any?): Boolean =
                other is Cell && x == other.x && y == other.y && z == other.z

            override fun hashCode(): Int {
                var result = x.hashCode()
                result = 31 * result + y.hashCode()
                return 31 * result + z.hashCode()
            }
        }

        private fun metersBetween(
            latitude1: Double,
            longitude1: Double,
            latitude2: Double,
            longitude2: Double
        ): Double {
            val lat1 = Math.toRadians(latitude1)
            val lat2 = Math.toRadians(latitude2)
            val x = Math.toRadians(longitude2 - longitude1) * cos((lat1 + lat2) / 2.0)
            val y = lat2 - lat1
            return EARTH_RADIUS_M * hypot(x, y)
        }

        private class DoubleBuffer(initialCapacity: Int) {
            private var values = DoubleArray(initialCapacity)
            var size = 0
                private set

            operator fun get(index: Int) = values[index]

            fun add(value: Double) {
                if (size == values.size) values = values.copyOf(values.size * 2)
                values[size++] = value
            }

            fun clear() { size = 0 }

            fun toArray() = values.copyOf(size)

            // Only use when this buffer is finished: the caller may own its
            // backing array. Reused split-road buffers must keep using toArray().
            fun finish() = if (size == values.size) values else values.copyOf(size)
        }

        private const val EARTH_RADIUS_M = 6_371_008.8
        private const val ENDPOINT_JOIN_TOLERANCE_M = 1.0
        private const val MAX_SUPPORTED_JUNCTION_TIME_MS = 15_000L
        private const val MAX_SUPPORTED_JUNCTION_DISTANCE_M = 30.0
        private const val MIN_JUNCTION_DIRECTION_M = 4.0
        private const val MAX_JUNCTION_TANGENT_LOOKBACK_M = 25.0
        private const val MIN_UNIQUE_JUNCTION_MARGIN_M = 3.0
        private const val DUPLICATE_ENDPOINT_M = 0.01
    }
}

/**
 * Off-screen fog renderer. The resulting bitmap is georeferenced by MapLibre's ImageSource, so
 * panning/zooming/rotation are handled by the native renderer instead of by an Android View.
 */
object FogBitmapRenderer {
    data class Request(
        val bitmapWidth: Int,
        val bitmapHeight: Int,
        val screenLeft: Float,
        val screenTop: Float,
        val screenScale: Float,
        val roads: OverlayRoads,
        val roadScreen: DoubleArray,
        val centerLatitude: Double,
        val metersPerScreenPixelAtCenter: Double,
        val liveLatitude: Double?,
        val liveScreen: DoubleArray?,
        val textureMatrix: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        val mediumCloudMatrix: FloatArray = textureMatrix,
        val broadCloudMatrix: FloatArray = textureMatrix,
        val exploredCoordinates: DoubleArray = doubleArrayOf(),
        val exploredScreen: DoubleArray = doubleArrayOf(),
        val gridMode: Boolean = false,
        val gridCoordinates: DoubleArray = doubleArrayOf(),
        val gridScreen: DoubleArray = doubleArrayOf()
    )

    fun render(request: Request, reusable: Bitmap? = null): Bitmap {
        val bitmap = if (reusable != null && !reusable.isRecycled &&
            reusable.width == request.bitmapWidth && reusable.height == request.bitmapHeight &&
            reusable.config == Bitmap.Config.ARGB_8888
        ) {
            reusable
        } else {
            reusable?.recycle()
            Bitmap.createBitmap(request.bitmapWidth, request.bitmapHeight, Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(bitmap)
        val scratch = requireNotNull(renderScratch.get())
        if (request.gridMode) {
            // All three cloud octaves use geographic Mercator texture matrices,
            // never the camera's previous pan or zoom. The dark, layered wisps
            // therefore travel WITH the map while the alpha remains exactly 80%.
            canvas.drawColor(STABLE_GRID_FOG_COLOR, PorterDuff.Mode.SRC)
            val metersPerPixel = request.metersPerScreenPixelAtCenter / request.screenScale
            scratch.drawClouds(canvas, request, metersPerPixel)
        } else {
            // Retain the legacy road-fog renderer for its independent unit tests.
            scratch.textureMatrix.setValues(request.textureMatrix)
            scratch.cloudShader.setLocalMatrix(scratch.textureMatrix)
            canvas.drawRect(0f, 0f, request.bitmapWidth.toFloat(), request.bitmapHeight.toFloat(), scratch.legacy.cloudPaint)
        }
        // In grid mode, road geometry and point-radius fog reveals do not contribute.
        // Blue roads still render separately; entering a mile tile is the sole unlock trigger.
        if (request.gridMode) {
            if (request.gridScreen.isEmpty()) return bitmap
            // Acquire the reveal mask only for the first tile that can affect
            // the bitmap; most nearby views have many saved tiles entirely offscreen.
            var reveal: Bitmap? = null
            var revealCanvas: Canvas? = null
            // These camera/raster parameters are constant for every tile in
            // this render. Compute the fade envelope only once, not per cell.
            val gridScale = request.screenScale.toDouble()
            val gridMetersPerPixel = request.metersPerScreenPixelAtCenter
            val gridFade = if (!gridScale.isFinite() || gridScale <= 0.0 ||
                !gridMetersPerPixel.isFinite() || gridMetersPerPixel <= 0.0
            ) Double.NaN else ROAD_FULL_M * gridScale / gridMetersPerPixel + 2.0
            for (i in 0 until minOf(request.gridCoordinates.size, request.gridScreen.size) - 7 step 8) {
                if (!gridCellMayAffectBitmap(request, i, gridScale, gridFade)) continue
                if (revealCanvas == null) {
                    reveal = scratch.acquireRevealBitmap(request.bitmapWidth, request.bitmapHeight)
                    revealCanvas = Canvas(reveal)
                }
                drawGridReveal(revealCanvas, request, i, scratch)
            }
            // No recorded tile affects this raster, even via its 1,500-foot fade:
            // avoid the bitmap-sized reveal mask and distance transform altogether.
            val mask = reveal ?: return bitmap
            // Merge every visited cell into ONE binary silhouette before fading. Individual
            // square edge/corner shaders create visible gray tiles and double seams where
            // neighboring cells meet. One distance-to-union mask has neither artifact.
            fadeGridUnion(mask, request, scratch)
            canvas.drawBitmap(mask, 0f, 0f, scratch.applyRevealPaint)
            return bitmap
        }
        if (request.roads.coordinates.isEmpty() && request.exploredCoordinates.isEmpty() && request.liveScreen == null) return bitmap

        // Combine the strongest reveal once. Repeated DST_OUT operations multiply the
        // remaining alpha, widening the clear area as roads/visited places overlap.
        val reveal = scratch.acquireRevealBitmap(request.bitmapWidth, request.bitmapHeight)
        val revealCanvas = Canvas(reveal)
        val erasePaint = scratch.revealPaint
        val legacy = scratch.legacy
        val capBounds = legacy.capBounds
        val roadLinear = legacy.roadLinear
        val roadRadial = legacy.roadRadial
        val locationRadial = legacy.locationRadial

        val centerCos = cos(Math.toRadians(request.centerLatitude.coerceIn(-85.05112878, 85.05112878)))
            .coerceAtLeast(1e-6)
        val metersPerPixelCosScale = request.metersPerScreenPixelAtCenter / centerCos
        for (road in 0 until request.roads.starts.size - 1) {
            for (i in request.roads.starts[road] until request.roads.starts[road + 1] - 2 step 2) {
                if (!request.roadScreen[i].isFinite() || !request.roadScreen[i + 1].isFinite() ||
                    !request.roadScreen[i + 2].isFinite() || !request.roadScreen[i + 3].isFinite()) continue
                val latitude = (request.roads.coordinates[i] + request.roads.coordinates[i + 2]) / 2.0
                val localMetersPerPixel = metersPerPixelCosScale *
                    cos(Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878)))
                if (!localMetersPerPixel.isFinite() || localMetersPerPixel <= 0) continue
                val radius = maxOf(
                    (ROAD_FULL_M / localMetersPerPixel * request.screenScale).toFloat(),
                    MIN_VISIBLE_REVEAL_RADIUS_PX * request.screenScale
                )
                drawRoadReveal(
                    revealCanvas,
                    ((request.roadScreen[i] - request.screenLeft) * request.screenScale).toFloat(),
                    ((request.roadScreen[i + 1] - request.screenTop) * request.screenScale).toFloat(),
                    ((request.roadScreen[i + 2] - request.screenLeft) * request.screenScale).toFloat(),
                    ((request.roadScreen[i + 3] - request.screenTop) * request.screenScale).toFloat(),
                    radius, erasePaint, roadLinear, roadRadial, capBounds
                )
            }
        }

        for (i in request.exploredCoordinates.indices step 2) {
            drawPlaceReveal(revealCanvas, request, request.exploredCoordinates[i], request.exploredScreen[i],
                request.exploredScreen[i + 1], metersPerPixelCosScale, erasePaint, locationRadial)
        }
        val live = request.liveScreen
        val liveLatitude = request.liveLatitude
        if (live != null && liveLatitude != null && live.all { it.isFinite() }) {
            drawPlaceReveal(revealCanvas, request, liveLatitude, live[0], live[1], metersPerPixelCosScale, erasePaint, locationRadial)
        }
        canvas.drawBitmap(reveal, 0f, 0f, scratch.applyRevealPaint)
        return bitmap
    }

    /**
     * Conservative culling before allocating a reveal mask or running the bitmap-wide
     * distance transform. Include the full physical fade plus two raster pixels for
     * antialiasing and subpixel cells. Reject only tiles that cannot touch the image.
     */
    internal fun gridCellMayAffectBitmap(request: Request, offset: Int): Boolean {
        if (offset < 0 || offset + 7 >= request.gridScreen.size) return false
        val scale = request.screenScale.toDouble()
        val metersPerPixel = request.metersPerScreenPixelAtCenter
        if (!scale.isFinite() || scale <= 0.0 ||
            !metersPerPixel.isFinite() || metersPerPixel <= 0.0
        ) return true // Preserve old rendering for unusual caller inputs.
        return gridCellMayAffectBitmap(request, offset, scale,
            ROAD_FULL_M * scale / metersPerPixel + 2.0)
    }

    private fun gridCellMayAffectBitmap(request: Request, offset: Int, scale: Double, fade: Double): Boolean {
        if (offset < 0 || offset + 7 >= request.gridScreen.size) return false
        if (fade.isNaN()) return true // The render path preserves unusual caller inputs.
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (corner in 0..3) {
            val x = (request.gridScreen[offset + corner * 2] - request.screenLeft) * scale
            val y = (request.gridScreen[offset + corner * 2 + 1] - request.screenTop) * scale
            if (!x.isFinite() || !y.isFinite()) return false
            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
        }
        return maxX >= -fade && minX <= request.bitmapWidth + fade &&
            maxY >= -fade && minY <= request.bitmapHeight + fade
    }

    /**
     * Rasterize only the core polygon. Every tile draws into one shared, opaque RGB
     * mask; neighboring tiles never have internal edge gradients or visible seams.
     */
    private fun drawGridReveal(canvas: Canvas, request: Request, offset: Int, scratch: RenderScratch) {
        val xy = scratch.gridPoints
        for (corner in 0..3) {
            val i = offset + corner * 2
            val x = request.gridScreen[i]
            val y = request.gridScreen[i + 1]
            if (!x.isFinite() || !y.isFinite()) return
            xy[corner * 2] = ((x - request.screenLeft) * request.screenScale).toFloat()
            xy[corner * 2 + 1] = ((y - request.screenTop) * request.screenScale).toFloat()
        }
        val path = scratch.gridPath
        path.reset()
        path.moveTo(xy[0], xy[1])
        for (i in 1..3) path.lineTo(xy[i * 2], xy[i * 2 + 1])
        path.close()
        val paint = scratch.revealPaint
        paint.shader = null
        paint.color = Color.WHITE
        canvas.drawPath(path, paint)
    }

    /**
     * Two-pass 8-neighbor Euclidean-distance approximation (1 and sqrt(2) pixel steps).
     * The only fade is from the OUTER boundary of the union, across 1,500 real feet.
     * All interior pixels stay fully transparent, with no seams even at a T junction.
     * Work is linear in bitmap pixels rather than in (tiles * pixels).
     */
    private fun fadeGridUnion(bitmap: Bitmap, request: Request, scratch: RenderScratch) {
        val width = bitmap.width
        val height = bitmap.height
        val count = width * height
        val pixels = scratch.gridPixels(count)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val physicalFadePixels = (ROAD_FULL_M * request.screenScale /
            request.metersPerScreenPixelAtCenter).toFloat()
        if (physicalFadePixels < 0.75f) {
            // At continental/world scale a mile square covers far less than one
            // bitmap pixel. Never turn *any* touched texel into a fully clear
            // world-sized block. Preserve the polygon's anti-aliased fractional
            // coverage instead; it naturally fades to imperceptible subpixels.
            for (i in 0 until count) {
                val coverage = pixels[i] and 0xff
                // Exactly the same opaque grayscale as Color.rgb(), without
                // a method call for every pixel of the world fog mask.
                pixels[i] = -0x1000000 or (coverage * 0x10101)
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            return
        }
        // At world zoom the subpixel branch above doesn't need a distance
        // buffer or the first full-bitmap scan. Keep both for the exact same
        // detailed-zoom fade below.
        val distances = scratch.gridDistances(count)
        val infinity = (width + height).toFloat()
        for (i in 0 until count) {
            // Anti-aliased polygon coverage counts as a visited pixel. This keeps
            // 1-pixel-scale cells perceptible on zoomed-out maps.
            distances[i] = if ((pixels[i] and 0xff) > 0) 0f else infinity
        }
        val diagonal = 1.41421356f
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                val i = base + x
                var distance = distances[i]
                if (x > 0) distance = minOf(distance, distances[i - 1] + 1f)
                if (y > 0) {
                    distance = minOf(distance, distances[i - width] + 1f)
                    if (x > 0) distance = minOf(distance, distances[i - width - 1] + diagonal)
                    if (x + 1 < width) distance = minOf(distance, distances[i - width + 1] + diagonal)
                }
                distances[i] = distance
            }
        }
        // One true physical 1,500-foot transition. Do not magnify the physical
        // fade to a mandatory 1.25-bitmap-pixel halo when zoomed far out.
        val radius = physicalFadePixels
        for (y in height - 1 downTo 0) {
            val base = y * width
            for (x in width - 1 downTo 0) {
                val i = base + x
                var distance = distances[i]
                if (x + 1 < width) distance = minOf(distance, distances[i + 1] + 1f)
                if (y + 1 < height) {
                    distance = minOf(distance, distances[i + width] + 1f)
                    if (x > 0) distance = minOf(distance, distances[i + width - 1] + diagonal)
                    if (x + 1 < width) distance = minOf(distance, distances[i + width + 1] + diagonal)
                }
                // The reverse scan MUST store each relaxed distance. Upper/left
                // pixels depend on already-processed lower/right neighbors; omitting
                // this assignment leaves half of the outer fade fully opaque.
                distances[i] = distance
                // This smooth easing removes gray/checkerboard edge bands caused by
                // separate side/corner shaders and low-resolution raster sampling.
                val fraction = (distance / radius).coerceIn(0f, 1f)
                val smooth = fraction * fraction * (3f - 2f * fraction)
                val shade = ((1f - smooth) * 255f).roundToInt().coerceIn(0, 255)
                pixels[i] = -0x1000000 or (shade * 0x10101)
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun drawPlaceReveal(canvas: Canvas, request: Request, latitude: Double, x: Double, y: Double,
                                metersPerPixelCosScale: Double, paint: Paint, gradient: RadialGradient) {
        if (!x.isFinite() || !y.isFinite()) return
        val metersPerPixel = metersPerPixelCosScale *
            cos(Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878)))
        val radius = maxOf(
            (LOCATION_FULL_M / metersPerPixel * request.screenScale).toFloat(),
            MIN_VISIBLE_REVEAL_RADIUS_PX * request.screenScale
        )
        if (!radius.isFinite() || radius <= 0f) return
        val drawX = ((x - request.screenLeft) * request.screenScale).toFloat()
        val drawY = ((y - request.screenTop) * request.screenScale).toFloat()
        if (drawX + radius < 0f || drawX - radius > canvas.width ||
            drawY + radius < 0f || drawY - radius > canvas.height
        ) return
        val save = canvas.save()
        canvas.translate(drawX, drawY)
        canvas.scale(radius, radius)
        paint.shader = gradient
        canvas.drawCircle(0f, 0f, 1f, paint)
        canvas.restoreToCount(save)
    }

    private fun drawRoadReveal(
        canvas: Canvas,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        radius: Float,
        erasePaint: Paint,
        roadLinear: LinearGradient,
        roadRadial: RadialGradient,
        capBounds: RectF
    ) {
        if (!radius.isFinite() || radius <= 0) return
        if (maxOf(x1, x2) + radius < 0f || minOf(x1, x2) - radius > canvas.width ||
            maxOf(y1, y2) + radius < 0f || minOf(y1, y2) - radius > canvas.height
        ) return
        val length = hypot(x2 - x1, y2 - y1)
        val save = canvas.save()
        canvas.translate(x1, y1)
        canvas.rotate(Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())).toFloat())
        canvas.scale(radius, radius)
        erasePaint.shader = roadLinear
        canvas.drawRect(0f, -1f, length / radius, 1f, erasePaint)
        erasePaint.shader = roadRadial
        canvas.drawArc(capBounds, 90f, 180f, true, erasePaint)
        canvas.translate(length / radius, 0f)
        canvas.drawArc(capBounds, -90f, 180f, true, erasePaint)
        canvas.restoreToCount(save)
    }

    private fun radial(innerFraction: Float) = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(
            Color.WHITE,
            Color.WHITE,
            Color.rgb(140, 140, 140),
            Color.rgb(51, 51, 51),
            Color.rgb(15, 15, 15),
            Color.BLACK
        ),
        floatArrayOf(0f, innerFraction, 0.20f, 0.50f, 0.80f, 1f),
        Shader.TileMode.CLAMP
    )

    const val ROAD_CLEAR_M = 50f * 0.3048f
    const val ROAD_FULL_M = 1500f * 0.3048f
    const val LOCATION_FULL_M = ROAD_FULL_M
    const val MAX_FOG_ALPHA = 0.80f
    // The entire visited-grid fog has ONE immutable tint, regardless of zoom or
    // panning. Underlying map styles may vary, but the fog's RGBA is stable.
    const val STABLE_GRID_FOG_ARGB: Int = -869717202 // #CC292B2E
    private val STABLE_GRID_FOG_COLOR = STABLE_GRID_FOG_ARGB
    // Fixed physical scales, not fixed screen pixels. Large weather masses are
    // visible at world zoom; fine diffuse cloud structure appears when closer.
    const val CLOUD_DETAIL_METERS = 12_000.0
    const val CLOUD_MEDIUM_METERS = 250_000.0
    const val CLOUD_BROAD_METERS = 6_400_000.0
    // Confirmed/pending blue roads stay visible farther out than the cleared fog corridor.
    const val MIN_ROAD_ZOOM = 6.0
    const val MIN_FOG_REVEAL_ZOOM = 3.5
    const val MAX_ZOOM = 20.0
    const val CENTER_ZOOM = 18.0
    const val MAX_BITMAP_DIMENSION = 1024
    const val VIEWPORT_PADDING_MULTIPLIER = 1.5f
    // Keep narrow road/place reveals perceptible at the farthest supported overview zoom
    // without changing their real-world fade distance at ordinary driving zoom levels.
    private const val MIN_VISIBLE_REVEAL_RADIUS_PX = 1.25f

    fun bitmapDimensionForZoom(zoom: Double): Int = when {
        zoom < MIN_FOG_REVEAL_ZOOM -> 512 // Full Mercator world, without oversized mask pixels.
        zoom < 12.0 -> 896 // Local raster, not a 512-pixel world blown up at regional zoom.
        else -> MAX_BITMAP_DIMENSION
    }

    private val cloudTexture by lazy { FogTexture.create() }

    private class RenderScratch {
        val textureMatrix = Matrix()
        val cloudShader = BitmapShader(cloudTexture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        // The app renders grid fog; initialize road-only native objects only for legacy requests.
        private var legacyScratch: LegacyRoadScratch? = null
        val legacy: LegacyRoadScratch
            get() = legacyScratch ?: LegacyRoadScratch(cloudShader).also { legacyScratch = it }
        private val mediumShader = BitmapShader(cloudTexture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        private val broadShader = BitmapShader(cloudTexture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        private val mediumMatrix = Matrix()
        private val broadMatrix = Matrix()
        // SRC_ATOP changes RGB without increasing the already fixed 80% fog alpha.
        private val detailMistPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            shader = cloudShader
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        }
        private val mediumMistPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            shader = mediumShader
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        }
        private val broadMistPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            shader = broadShader
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        }

        fun drawClouds(canvas: Canvas, request: Request, metersPerPixel: Double) {
            // Don't alias a 12-km cloud texture down to one sample per 78-km
            // world texel. The broader anchored layers still provide weather-like
            // variation when the viewer sees the entire world.
            fun cloudStrength(scale: Double, alpha: Int): Int =
                (alpha * (scale / (metersPerPixel * 48.0)).coerceIn(0.0, 1.0)).roundToInt()
            val detail = cloudStrength(CLOUD_DETAIL_METERS, 140)
            val medium = cloudStrength(CLOUD_MEDIUM_METERS, 110)
            val broad = cloudStrength(CLOUD_BROAD_METERS, 90)
            val width = request.bitmapWidth.toFloat()
            val height = request.bitmapHeight.toFloat()
            // Broad bodies first; medium diffuse banks and close-up wisps over them.
            if (broad > 0) {
                broadMatrix.setValues(request.broadCloudMatrix)
                broadShader.setLocalMatrix(broadMatrix)
                broadMistPaint.alpha = broad
                canvas.drawRect(0f, 0f, width, height, broadMistPaint)
            }
            if (medium > 0) {
                mediumMatrix.setValues(request.mediumCloudMatrix)
                mediumShader.setLocalMatrix(mediumMatrix)
                mediumMistPaint.alpha = medium
                canvas.drawRect(0f, 0f, width, height, mediumMistPaint)
            }
            if (detail > 0) {
                textureMatrix.setValues(request.textureMatrix)
                cloudShader.setLocalMatrix(textureMatrix)
                detailMistPaint.alpha = detail
                canvas.drawRect(0f, 0f, width, height, detailMistPaint)
            }
        }
        val revealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
        }
        val applyRevealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            colorFilter = ColorMatrixColorFilter(floatArrayOf(
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                1f, 0f, 0f, 0f, 0f
            ))
        }
        val gridPoints = FloatArray(8)
        val gridPath = Path()
        private var gridPixelCache = IntArray(0)
        private var gridDistanceCache = FloatArray(0)

        fun gridPixels(count: Int): IntArray {
            if (gridPixelCache.size < count) gridPixelCache = IntArray(count)
            return gridPixelCache
        }

        fun gridDistances(count: Int): FloatArray {
            if (gridDistanceCache.size < count) gridDistanceCache = FloatArray(count)
            return gridDistanceCache
        }
        private var revealBitmap: Bitmap? = null

        fun acquireRevealBitmap(width: Int, height: Int): Bitmap {
            val existing = revealBitmap
            if (existing != null && !existing.isRecycled && existing.width == width && existing.height == height) {
                existing.eraseColor(Color.BLACK)
                return existing
            }
            existing?.recycle()
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.BLACK)
                revealBitmap = it
            }
        }
    }

    private class LegacyRoadScratch(cloudShader: BitmapShader) {
        val cloudPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            shader = cloudShader
            alpha = (MAX_FOG_ALPHA * 255).roundToInt()
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }
        val capBounds = RectF(-1f, -1f, 1f, 1f)
        private val roadClearFraction = ROAD_CLEAR_M / ROAD_FULL_M
        val roadLinear = LinearGradient(
            0f, -1f, 0f, 1f,
            intArrayOf(
                Color.BLACK,
                Color.rgb(15, 15, 15),
                Color.rgb(51, 51, 51),
                Color.rgb(140, 140, 140),
                Color.WHITE,
                Color.WHITE,
                Color.rgb(140, 140, 140),
                Color.rgb(51, 51, 51),
                Color.rgb(15, 15, 15),
                Color.BLACK
            ),
            floatArrayOf(
                0f,
                0.10f,
                0.25f,
                0.40f,
                (1f - roadClearFraction) / 2f,
                (1f + roadClearFraction) / 2f,
                0.60f,
                0.75f,
                0.90f,
                1f
            ),
            Shader.TileMode.CLAMP
        )
        val roadRadial = radial(ROAD_CLEAR_M / ROAD_FULL_M)
        // Road and place reveals intentionally use the same clear/full radii, so the exact
        // same immutable shader can serve both instead of allocating a duplicate gradient.
        val locationRadial = roadRadial
    }

    private val renderScratch = ThreadLocal.withInitial { RenderScratch() }
}
