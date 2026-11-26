package com.masoud.glasscounter

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Vertical glass-boundary consensus.
 *
 * The supplied factory photographs show near-vertical glass edges. The engine
 * samples independent horizontal bands, detects strong vertical transitions,
 * and votes for slanted x(y) tracks shared by multiple bands. A panel is the
 * interval between two consecutive supported glass boundaries.
 *
 * This is a classical-CV prototype. Its confidence is a consistency score,
 * not a calibrated probability or proof of industrial accuracy.
 */
internal object GlassCountingEngine {
    data class Result(
        val count: Int,
        val detectedEdges: Int,
        val pairSpacingPx: Int,
        val confidence: Float
    )

    private const val MAX_WIDTH = 1000
    private const val BAND_COUNT = 15
    private const val SLOPE_BINS = 41
    private const val MAX_SLOPE_FRACTION = 0.035f
    private const val MIN_BAND_SUPPORT = 8

    private data class Edge(val x: Int, val strength: Float)
    private data class Track(val intercept: Int, val slope: Float, val support: Int)

    fun analyze(source: Bitmap): Result {
        if (source.width < 100 || source.height < 100) return emptyResult()
        val width = min(MAX_WIDTH, source.width)
        val height = max(100, source.height * width / source.width)
        val image = if (width == source.width && height == source.height) source
            else Bitmap.createScaledBitmap(source, width, height, true)

        try {
            val left = width / 25
            val right = width * 24 / 25
            val top = height / 25
            val bottom = height * 24 / 25
            val usableWidth = right - left
            val usableHeight = bottom - top
            if (usableWidth < 80 || usableHeight < 80) return emptyResult()

            val bandEdges = ArrayList<List<Edge>>(BAND_COUNT)
            val bandCenters = FloatArray(BAND_COUNT)
            for (band in 0 until BAND_COUNT) {
                val y0 = top + usableHeight * band / BAND_COUNT
                val y1 = top + usableHeight * (band + 1) / BAND_COUNT
                bandCenters[band] = ((y0 + y1) * 0.5f / height - 0.5f) * 2f
                bandEdges.add(detectVerticalEdges(image, left, right, y0, y1))
            }

            // Hough-style consensus for x = intercept + slope * normalizedY.
            val accumulator = Array(SLOPE_BINS) { IntArray(usableWidth) }
            for (band in 0 until BAND_COUNT) {
                val yNorm = bandCenters[band]
                for (edge in bandEdges[band]) {
                    for (s in 0 until SLOPE_BINS) {
                        val slope = slopeAt(s, usableHeight)
                        val intercept = (edge.x - left - slope * yNorm).toInt()
                        if (intercept in 0 until usableWidth) accumulator[s][intercept]++
                    }
                }
            }

            val raw = ArrayList<Track>()
            for (s in 0 until SLOPE_BINS) {
                val row = accumulator[s]
                for (x in 2 until usableWidth - 2) {
                    val votes = row[x]
                    if (votes >= MIN_BAND_SUPPORT &&
                        votes >= row[x - 1] && votes > row[x + 1]
                    ) raw.add(Track(x, slopeAt(s, usableHeight), votes))
                }
            }

            // Keep one best slope/intercept hypothesis for each physical edge.
            val candidates = ArrayList<Track>()
            for (candidate in raw.sortedByDescending { it.support }) {
                val duplicate = candidates.any {
                    abs(it.intercept - candidate.intercept) <= max(2, usableWidth / 300) &&
                        abs(it.slope - candidate.slope) <= 0.012f * usableHeight
                }
                if (!duplicate) candidates.add(candidate)
            }

            // Recheck against distinct bands; local reflections cannot vote as
            // a full-height glass boundary unless they recur through the stack.
            val verified = candidates.mapNotNull { track ->
                var support = 0
                for (band in 0 until BAND_COUNT) {
                    val predicted = left + track.intercept + track.slope * bandCenters[band]
                    if (bandEdges[band].any {
                        abs(it.x - predicted) <= max(2, usableWidth / 250)
                    }) support++
                }
                if (support >= MIN_BAND_SUPPORT) track.copy(support = support) else null
            }.sortedBy { it.intercept }

            // Merge duplicate detections caused by both sides of one bright ridge.
            val boundaries = ArrayList<Track>()
            val mergeDistance = max(2, usableWidth / 260)
            for (track in verified) {
                val previous = boundaries.lastOrNull()
                if (previous == null ||
                    track.intercept - previous.intercept > mergeDistance
                ) {
                    boundaries.add(track)
                } else if (track.support > previous.support) {
                    boundaries[boundaries.lastIndex] = track
                }
            }

            if (boundaries.size < 2) return Result(0, boundaries.size, 0, 0f)
            val gaps = boundaries.zipWithNext { a, b -> b.intercept - a.intercept }
                .filter { it > 0 }
            val spacing = if (gaps.isEmpty()) 0 else gaps.sorted()[gaps.size / 2]
            val consistency = boundaries.map {
                it.support.toFloat() / BAND_COUNT
            }.average().toFloat()
            val densityScore = min(1f, boundaries.size / 12f)
            val confidence = (consistency * densityScore).coerceIn(0f, 1f)

            return Result(
                count = (boundaries.size - 1).coerceAtMost(300),
                detectedEdges = boundaries.size,
                pairSpacingPx = spacing,
                confidence = confidence
            )
        } finally {
            if (image !== source) image.recycle()
        }
    }

    private fun detectVerticalEdges(
        image: Bitmap,
        xStart: Int,
        xEnd: Int,
        yStart: Int,
        yEnd: Int
    ): List<Edge> {
        val profile = FloatArray(image.width)
        val rowStep = max(1, (yEnd - yStart) / 90)
        for (x in xStart + 2 until xEnd - 2) {
            var total = 0f
            var samples = 0
            var y = yStart
            while (y < yEnd) {
                val left = luminance(image.getPixel(x - 2, y))
                val right = luminance(image.getPixel(x + 2, y))
                val nearLeft = luminance(image.getPixel(x - 1, y))
                val nearRight = luminance(image.getPixel(x + 1, y))
                total += abs(right - left) * 0.7f + abs(nearRight - nearLeft) * 0.3f
                samples++
                y += rowStep
            }
            profile[x] = if (samples == 0) 0f else total / samples
        }

        val values = profile.sliceArray(xStart + 3 until xEnd - 3).sorted()
        if (values.isEmpty()) return emptyList()
        val median = values[values.size / 2]
        val deviations = values.map { abs(it - median) }.sorted()
        val mad = deviations[deviations.size / 2]
        val p78 = values[(values.size * 78 / 100).coerceIn(0, values.lastIndex)]
        val threshold = max(median + max(1.5f, mad * 1.8f), p78)

        val peaks = ArrayList<Edge>()
        for (x in xStart + 3 until xEnd - 3) {
            if (profile[x] >= threshold &&
                profile[x] >= profile[x - 1] && profile[x] > profile[x + 1]
            ) peaks.add(Edge(x, profile[x]))
        }

        val chosen = ArrayList<Edge>()
        val mergeDistance = max(2, image.width / 450)
        for (edge in peaks.sortedByDescending { it.strength }) {
            if (chosen.none { abs(it.x - edge.x) <= mergeDistance }) chosen.add(edge)
        }
        return chosen
    }

    private fun slopeAt(index: Int, usableHeight: Int): Float {
        val fraction = index.toFloat() / (SLOPE_BINS - 1)
        return (fraction * 2f - 1f) * MAX_SLOPE_FRACTION * usableHeight
    }

    private fun luminance(color: Int): Float =
        Color.red(color) * 0.299f + Color.green(color) * 0.587f + Color.blue(color) * 0.114f

    private fun emptyResult() = Result(0, 0, 0, 0f)
}
