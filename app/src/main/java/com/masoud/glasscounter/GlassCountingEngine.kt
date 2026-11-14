package com.masoud.glasscounter

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-band edge-consensus counter.
 *
 * Replaces the old "find a common edge-pair distance, then greedily pair peaks"
 * approach. Each horizontal image band independently votes for persistent
 * vertical boundaries. Only boundaries supported in several separated bands
 * are retained. This is still a deterministic CV prototype, not a trained model.
 */
internal object GlassCountingEngine {
    data class Result(
        val count: Int,
        val detectedEdges: Int,
        val pairSpacingPx: Int,
        val confidence: Float
    )

    private const val MAX_WIDTH = 1400
    private const val BAND_COUNT = 9
    private const val MIN_SUPPORTED_BANDS = 3

    fun analyze(source: Bitmap): Result {
        if (source.width < 80 || source.height < 100) return emptyResult()

        val width = min(MAX_WIDTH, source.width)
        val height = max(160, source.height * width / source.width)
        val image = if (width == source.width && height == source.height) source
            else Bitmap.createScaledBitmap(source, width, height, true)

        try {
            val bands = ArrayList<IntArray>(BAND_COUNT)
            val bandTop = height / 12
            val bandBottom = height * 11 / 12
            val bandHeight = max(1, (bandBottom - bandTop) / BAND_COUNT)
            val mergeRadius = max(2, width / 500)

            // Each band is analyzed independently so a reflection in one part
            // of the stack cannot dominate the whole image.
            for (band in 0 until BAND_COUNT) {
                val y0 = bandTop + band * bandHeight
                val y1 = if (band == BAND_COUNT - 1) bandBottom else y0 + bandHeight
                bands.add(detectBandEdges(image, y0, y1, mergeRadius))
            }

            // Cross-band consensus: one edge gets at most one vote per band.
            val votes = IntArray(width)
            val strength = FloatArray(width)
            for (edges in bands) {
                for (x in edges) {
                    val from = max(0, x - mergeRadius)
                    val to = min(width - 1, x + mergeRadius)
                    var best = x
                    for (p in from..to) {
                        if (edgeStrength(image, p, bandTop, bandBottom) >
                            edgeStrength(image, best, bandTop, bandBottom)) best = p
                    }
                    votes[best]++
                    strength[best] += 1f
                }
            }

            val requiredVotes = max(MIN_SUPPORTED_BANDS, (BAND_COUNT * 0.34f).toInt())
            val candidates = ArrayList<Int>()
            for (x in 2 until width - 2) {
                if (votes[x] < requiredVotes) continue
                if (votes[x] >= votes[x - 1] && votes[x] > votes[x + 1]) {
                    candidates.add(x)
                }
            }

            // Merge duplicate responses that represent the same physical line.
            val stableLines = ArrayList<Int>()
            for (x in candidates.sortedByDescending { votes[it] * 1000f + strength[it] }) {
                if (stableLines.none { abs(it - x) <= mergeRadius * 2 }) stableLines.add(x)
            }
            stableLines.sort()

            if (stableLines.isEmpty()) return Result(0, 0, 0, 0f)

            // Estimate typical separation only as a diagnostic. It is no longer
            // used to manufacture pairs or determine the count.
            val gaps = stableLines.zipWithNext { a, b -> b - a }.filter { it > mergeRadius * 2 }
            val spacing = if (gaps.isEmpty()) 0 else gaps.sorted()[gaps.size / 2]

            val support = stableLines.map { votes[it].toFloat() / BAND_COUNT }.average().toFloat()
            val coverage = (stableLines.size.toFloat() / max(1, width / mergeRadius)).coerceIn(0f, 1f)
            val confidence = (support * (0.65f + 0.35f * coverage)).coerceIn(0f, 1f)

            return Result(stableLines.size.coerceAtMost(300), stableLines.size, spacing, confidence)
        } finally {
            if (image !== source) image.recycle()
        }
    }

    private fun detectBandEdges(
        image: Bitmap,
        yStart: Int,
        yEnd: Int,
        mergeRadius: Int
    ): IntArray {
        val width = image.width
        val profile = FloatArray(width)
        val stride = max(1, (yEnd - yStart) / 180)
        for (x in 2 until width - 2) {
            var sum = 0f
            var samples = 0
            var y = yStart
            while (y < yEnd) {
                val left = luminance(image.getPixel(x - 2, y))
                val right = luminance(image.getPixel(x + 2, y))
                val centerLeft = luminance(image.getPixel(x - 1, y))
                val centerRight = luminance(image.getPixel(x + 1, y))
                // Combine a wider baseline with a local gradient. This reduces
                // sensitivity to tiny pixel noise while retaining thin glass edges.
                sum += abs(right - left) * 0.65f + abs(centerRight - centerLeft) * 0.35f
                samples++
                y += stride
            }
            profile[x] = if (samples == 0) 0f else sum / samples
        }

        val values = profile.sliceArray(3 until width - 3).sorted()
        if (values.isEmpty()) return intArrayOf()
        val median = values[values.size / 2]
        val deviations = values.map { abs(it - median) }.sorted()
        val mad = deviations[deviations.size / 2]
        val p82 = values[(values.size * 82 / 100).coerceIn(0, values.lastIndex)]
        val threshold = max(median + max(1.8f, mad * 1.8f), p82)

        val peaks = ArrayList<Int>()
        for (x in 3 until width - 3) {
            if (profile[x] >= threshold &&
                profile[x] >= profile[x - 1] &&
                profile[x] > profile[x + 1]
            ) peaks.add(x)
        }

        val selected = ArrayList<Int>()
        for (x in peaks.sortedByDescending { profile[it] }) {
            if (selected.none { abs(it - x) <= mergeRadius }) selected.add(x)
        }
        return selected.toIntArray()
    }

    private fun edgeStrength(image: Bitmap, x: Int, y0: Int, y1: Int): Float {
        if (x < 2 || x >= image.width - 2) return 0f
        val stride = max(1, (y1 - y0) / 100)
        var total = 0f
        var n = 0
        var y = y0
        while (y < y1) {
            total += abs(luminance(image.getPixel(x + 2, y)) -
                luminance(image.getPixel(x - 2, y)))
            n++
            y += stride
        }
        return if (n == 0) 0f else total / n
    }

    private fun luminance(color: Int): Float =
        Color.red(color) * 0.299f + Color.green(color) * 0.587f + Color.blue(color) * 0.114f

    private fun emptyResult() = Result(0, 0, 0, 0f)
}
