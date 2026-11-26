package com.masoud.glasscounter

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Glass-stack boundary counter (row-edge / slanted-line consensus).
 *
 * The previous implementation searched vertical image edges and returned the
 * number of edge columns. A stack of upright glass sheets is represented by
 * repeated boundaries running mostly across the image, so that orientation
 * was fundamentally wrong for the stated capture geometry.
 *
 * This version:
 *  1. Samples independent vertical image strips.
 *  2. Finds horizontal intensity transitions inside each strip.
 *  3. Uses a small Hough-style slope/intercept accumulator so tilted boundaries
 *     can receive votes even when their y coordinate changes across the image.
 *  4. Keeps only boundaries independently supported by several strips.
 *  5. Counts intervals between consecutive boundaries (N boundaries -> N-1
 *     visible intervals), rather than counting every edge as a glass.
 *
 * This is a deterministic CV prototype. It does not yet solve all reflections,
 * occlusions, perspective, or empty-gap classification; confidence is a
 * support score, not a calibrated probability.
 */
internal object GlassCountingEngine {
    data class Result(
        val count: Int,
        val detectedEdges: Int,
        val pairSpacingPx: Int,
        val confidence: Float
    )

    private const val MAX_WIDTH = 1400
    private const val STRIP_COUNT = 13
    private const val MIN_STRIP_SUPPORT = 4
    private const val SLOPE_BINS = 49
    private const val MAX_SLOPE_FRACTION = 0.12f

    private data class Edge(val y: Int, val strength: Float)
    private data class Line(val intercept: Int, val slope: Float, val votes: Int)

    fun analyze(source: Bitmap): Result {
        if (source.width < 80 || source.height < 100) return emptyResult()

        val width = min(MAX_WIDTH, source.width)
        val height = max(160, source.height * width / source.width)
        val image = if (width == source.width && height == source.height) source
            else Bitmap.createScaledBitmap(source, width, height, true)

        try {
            val top = height / 20
            val bottom = height * 19 / 20
            val usableHeight = bottom - top
            if (usableHeight < 80) return emptyResult()

            val accumulator = Array(SLOPE_BINS) { IntArray(usableHeight) }
            val stripEdges = ArrayList<List<Edge>>(STRIP_COUNT)
            val stripCenters = FloatArray(STRIP_COUNT)

            for (strip in 0 until STRIP_COUNT) {
                val x0 = width * strip / STRIP_COUNT
                val x1 = width * (strip + 1) / STRIP_COUNT
                val inset = max(1, (x1 - x0) / 10)
                val left = min(width - 2, x0 + inset)
                val right = max(left + 1, x1 - inset)
                stripCenters[strip] = ((left + right) * 0.5f / width - 0.5f) * 2f
                stripEdges.add(detectHorizontalEdges(image, left, right, top, bottom))
            }

            // Each local edge votes for all plausible line slopes. This allows
            // the same physical, slightly tilted boundary to agree across strips.
            for (strip in 0 until STRIP_COUNT) {
                val xNorm = stripCenters[strip]
                for (edge in stripEdges[strip]) {
                    for (s in 0 until SLOPE_BINS) {
                        val slope = slopeAt(s, usableHeight)
                        val intercept = (edge.y - top - slope * xNorm).toInt()
                        if (intercept in 0 until usableHeight) accumulator[s][intercept]++
                    }
                }
            }

            val minVotes = max(MIN_STRIP_SUPPORT, (STRIP_COUNT * 0.30f).toInt())
            val raw = ArrayList<Line>()
            for (s in 0 until SLOPE_BINS) {
                val row = accumulator[s]
                for (y in 2 until usableHeight - 2) {
                    val v = row[y]
                    if (v < minVotes || v < row[y - 1] || v <= row[y + 1]) continue
                    raw.add(Line(y, slopeAt(s, usableHeight), v))
                }
            }

            // Non-maximum suppression in intercept/slope space prevents one
            // physical boundary from being counted several times.
            val selected = ArrayList<Line>()
            for (candidate in raw.sortedByDescending { it.votes }) {
                val duplicate = selected.any {
                    abs(it.intercept - candidate.intercept) <= max(3, usableHeight / 180) &&
                        abs(it.slope - candidate.slope) <= 0.035f
                }
                if (!duplicate) selected.add(candidate)
            }

            // Refine support by checking how many distinct strips contain a
            // nearby edge at the predicted y coordinate.
            val supported = selected.mapNotNull { line ->
                var support = 0
                for (strip in 0 until STRIP_COUNT) {
                    val predicted = top + line.intercept + line.slope * stripCenters[strip]
                    if (stripEdges[strip].any { abs(it.y - predicted) <= max(3, usableHeight / 160) }) {
                        support++
                    }
                }
                if (support >= minVotes) line.copy(votes = support) else null
            }.sortedBy { it.intercept }

            // Collapse close parallel detections after refinement.
            val boundaries = ArrayList<Line>()
            for (line in supported) {
                val previous = boundaries.lastOrNull()
                if (previous == null || abs(previous.intercept - line.intercept) > max(4, usableHeight / 120)) {
                    boundaries.add(line)
                } else if (line.votes > previous.votes) {
                    boundaries[boundaries.lastIndex] = line
                }
            }

            if (boundaries.size < 2) return Result(0, boundaries.size, 0, 0f)

            val gaps = boundaries.zipWithNext { a, b -> b.intercept - a.intercept }
                .filter { it > 0 }
            val spacing = if (gaps.isEmpty()) 0 else gaps.sorted()[gaps.size / 2]
            val support = boundaries.map { it.votes.toFloat() / STRIP_COUNT }.average().toFloat()
            val confidence = (support * min(1f, boundaries.size / 8f)).coerceIn(0f, 1f)

            // A panel occupies the interval between two consecutive boundaries.
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

    private fun detectHorizontalEdges(
        image: Bitmap,
        xStart: Int,
        xEnd: Int,
        yStart: Int,
        yEnd: Int
    ): List<Edge> {
        val height = image.height
        val profile = FloatArray(height)
        val stepX = max(1, (xEnd - xStart) / 100)
        for (y in yStart + 2 until yEnd - 2) {
            var total = 0f
            var samples = 0
            var x = xStart
            while (x < xEnd) {
                val above = luminance(image.getPixel(x, y - 2))
                val below = luminance(image.getPixel(x, y + 2))
                val nearAbove = luminance(image.getPixel(x, y - 1))
                val nearBelow = luminance(image.getPixel(x, y + 1))
                total += abs(below - above) * 0.7f + abs(nearBelow - nearAbove) * 0.3f
                samples++
                x += stepX
            }
            profile[y] = if (samples == 0) 0f else total / samples
        }

        val values = profile.sliceArray(yStart + 3 until yEnd - 3).sorted()
        if (values.isEmpty()) return emptyList()
        val median = values[values.size / 2]
        val deviations = values.map { abs(it - median) }.sorted()
        val mad = deviations[deviations.size / 2]
        val p84 = values[(values.size * 84 / 100).coerceIn(0, values.lastIndex)]
        val threshold = max(median + max(2.0f, mad * 2.0f), p84)

        val peaks = ArrayList<Edge>()
        for (y in yStart + 3 until yEnd - 3) {
            if (profile[y] >= threshold &&
                profile[y] >= profile[y - 1] &&
                profile[y] > profile[y + 1]
            ) peaks.add(Edge(y, profile[y]))
        }

        val chosen = ArrayList<Edge>()
        val mergeDistance = max(2, image.height / 500)
        for (edge in peaks.sortedByDescending { it.strength }) {
            if (chosen.none { abs(it.y - edge.y) <= mergeDistance }) chosen.add(edge)
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
