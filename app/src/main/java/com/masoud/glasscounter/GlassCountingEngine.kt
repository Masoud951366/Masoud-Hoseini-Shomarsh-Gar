package com.masoud.glasscounter

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Horizontal-boundary consensus prototype for vertically stacked glass.
 * A boundary is accepted only when independent image columns support it.
 * Count is the number of consecutive intervals between accepted boundaries.
 */
internal object GlassCountingEngine {
    data class Result(
        val count: Int,
        val detectedEdges: Int,
        val pairSpacingPx: Int,
        val confidence: Float
    )

    private const val MAX_WIDTH = 1200
    private const val STRIP_COUNT = 9
    private const val MIN_STRIP_VOTES = 3

    fun analyze(source: Bitmap): Result {
        if (source.width < 100 || source.height < 100) return emptyResult()
        val width = min(MAX_WIDTH, source.width)
        val height = max(100, source.height * width / source.width)
        val image = if (width == source.width && height == source.height) source
            else Bitmap.createScaledBitmap(source, width, height, true)
        try {
            val top = height / 20
            val bottom = height * 19 / 20
            val votes = IntArray(height)
            val strengths = FloatArray(height)
            val radius = max(2, height / 500)
            val stripWidth = max(1, (width * 9 / 10) / STRIP_COUNT)
            for (strip in 0 until STRIP_COUNT) {
                val x0 = width / 20 + strip * stripWidth
                val x1 = min(width - 2, x0 + stripWidth)
                val profile = FloatArray(height)
                val stride = max(1, (x1 - x0) / 160)
                for (y in top + 2 until bottom - 2) {
                    var sum = 0f
                    var n = 0
                    var x = x0
                    while (x < x1) {
                        val a = lum(image.getPixel(x, y - 2))
                        val b = lum(image.getPixel(x, y + 2))
                        val c = lum(image.getPixel(x, y - 1))
                        val d = lum(image.getPixel(x, y + 1))
                        sum += abs(b - a) * 0.7f + abs(d - c) * 0.3f
                        n++
                        x += stride
                    }
                    profile[y] = if (n == 0) 0f else sum / n
                }
                val sorted = profile.sliceArray(top + 3 until bottom - 3).sorted()
                if (sorted.isEmpty()) continue
                val median = sorted[sorted.size / 2]
                val deviations = sorted.map { abs(it - median) }.sorted()
                val mad = deviations[deviations.size / 2]
                val p78 = sorted[(sorted.size * 78 / 100).coerceIn(0, sorted.lastIndex)]
                val threshold = max(median + max(1.2f, mad * 1.6f), p78)
                val peaks = ArrayList<Int>()
                for (y in top + 3 until bottom - 3) {
                    if (profile[y] >= threshold && profile[y] >= profile[y - 1] && profile[y] > profile[y + 1]) {
                        peaks.add(y)
                    }
                }
                val chosen = ArrayList<Int>()
                for (y in peaks.sortedByDescending { profile[it] }) {
                    if (chosen.none { abs(it - y) <= radius }) chosen.add(y)
                }
                for (y in chosen) {
                    val lo = max(top, y - radius)
                    val hi = min(bottom - 1, y + radius)
                    var best = y
                    for (p in lo..hi) if (profile[p] > profile[best]) best = p
                    votes[best]++
                    strengths[best] += profile[best]
                }
            }

            val candidates = (top + 2 until bottom - 2).filter { y ->
                votes[y] >= MIN_STRIP_VOTES && votes[y] >= votes[y - 1] && votes[y] > votes[y + 1]
            }.sortedByDescending { votes[it] * 10000f + strengths[it] }
            val accepted = ArrayList<Int>()
            for (y in candidates) if (accepted.none { abs(it - y) <= radius * 2 }) accepted.add(y)
            accepted.sort()
            if (accepted.size < 2) return Result(0, accepted.size, 0, 0f)

            val gaps = accepted.zipWithNext { a, b -> b - a }.filter { it > radius * 3 }
            val spacing = if (gaps.isEmpty()) 0 else gaps.sorted()[gaps.size / 2]
            val support = accepted.map { votes[it].toFloat() / STRIP_COUNT }.average().toFloat()
            val gapConsistency = if (gaps.size < 2 || spacing == 0) 0.35f else {
                val deviations = gaps.map { abs(it - spacing).toFloat() / spacing }.average().toFloat()
                (1f - deviations).coerceIn(0f, 1f)
            }
            val confidence = (support * 0.75f + gapConsistency * 0.25f).coerceIn(0f, 1f)
            return Result((accepted.size - 1).coerceAtMost(300), accepted.size, spacing, confidence)
        } finally {
            if (image !== source) image.recycle()
        }
    }

    private fun lum(c: Int): Float =
        Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f

    private fun emptyResult() = Result(0, 0, 0, 0f)
}
