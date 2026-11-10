package com.masoud.glasscounter

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Experimental glass-edge pair counter.
 *
 * A glass is represented by two neighboring vertical edge responses. The engine
 * builds a robust horizontal edge profile from multiple height bands, detects
 * prominent peaks, then looks for a repeated within-glass pair distance.
 *
 * This is a classical image-processing prototype, not a trained ML model.
 */
internal object GlassCountingEngine {
    data class Result(
        val count: Int,
        val detectedEdges: Int,
        val pairSpacingPx: Int,
        val confidence: Float
    )

    fun analyze(source: Bitmap): Result {
        if (source.width < 80 || source.height < 100) return Result(0, 0, 0, 0f)

        val width = min(1200, source.width)
        val height = max(160, source.height * width / source.width)
        val image = Bitmap.createScaledBitmap(source, width, height, true)
        try {
            val bandCount = 7
            val profiles = Array(bandCount) { FloatArray(width) }
            val top = height / 10
            val bottom = height * 9 / 10

            for (b in 0 until bandCount) {
                val yStart = top + (bottom - top) * b / bandCount
                val yEnd = top + (bottom - top) * (b + 1) / bandCount
                val stride = max(1, (yEnd - yStart) / 100)
                for (x in 2 until width - 2) {
                    var sum = 0.0
                    var samples = 0
                    var y = yStart
                    while (y < yEnd) {
                        val left = luminance(image.getPixel(x - 1, y))
                        val right = luminance(image.getPixel(x + 1, y))
                        val vertical = abs(right - left)
                        val above = luminance(image.getPixel(x, max(0, y - 1)))
                        val below = luminance(image.getPixel(x, min(height - 1, y + 1)))
                        // Favor vertical boundaries and suppress horizontal texture.
                        sum += max(0.0, vertical - abs(below - above) * 0.35)
                        samples++
                        y += stride
                    }
                    profiles[b][x] = if (samples == 0) 0f else (sum / samples).toFloat()
                }
            }

            val profile = FloatArray(width)
            for (x in 3 until width - 3) {
                val v = FloatArray(bandCount) { profiles[it][x] }.sorted()
                profile[x] = v[bandCount / 2]
            }

            val smooth = FloatArray(width)
            for (x in 3 until width - 3) {
                smooth[x] = (profile[x - 2] + 2f * profile[x - 1] +
                    3f * profile[x] + 2f * profile[x + 1] + profile[x + 2]) / 9f
            }

            val valid = smooth.sliceArray(4 until width - 4).sorted()
            if (valid.isEmpty()) return Result(0, 0, 0, 0f)
            val median = valid[valid.size / 2]
            val deviations = valid.map { abs(it - median) }.sorted()
            val mad = deviations[deviations.size / 2]
            val percentile = valid[(valid.size * 78 / 100).coerceIn(0, valid.lastIndex)]
            val threshold = max(median + max(2.0f, mad * 2.2f), percentile)

            val raw = ArrayList<Int>()
            for (x in 5 until width - 5) {
                if (smooth[x] >= threshold &&
                    smooth[x] >= smooth[x - 1] && smooth[x] > smooth[x + 1]) raw.add(x)
            }
            // Non-maximum suppression: keep one response per narrow edge cluster.
            val candidates = ArrayList<Int>()
            for (x in raw.sortedByDescending { smooth[it] }) {
                if (candidates.none { abs(it - x) < max(2, width / 700) }) candidates.add(x)
            }
            candidates.sort()
            if (candidates.size < 2) return Result(0, candidates.size, 0, 0f)

            // Find the most frequently repeated plausible distance between the
            // two boundaries of one glass. Distances are image-space pixels.
            val maxGap = max(4, width / 12)
            val histogram = IntArray(maxGap + 1)
            for (i in candidates.indices) {
                for (j in i + 1 until candidates.size) {
                    val gap = candidates[j] - candidates[i]
                    if (gap > maxGap) break
                    if (gap >= 2) histogram[gap]++
                }
            }
            var bestGap = 0
            var bestVotes = 0
            for (gap in 2..maxGap) {
                if (histogram[gap] > bestVotes) {
                    bestVotes = histogram[gap]
                    bestGap = gap
                }
            }
            if (bestGap == 0 || bestVotes == 0) return Result(0, candidates.size, 0, 0f)

            // Match disjoint pairs near the dominant spacing. Pairing is ordered
            // left-to-right to avoid counting one edge more than once.
            val tolerance = max(2, bestGap / 4)
            val used = HashSet<Int>()
            var pairs = 0
            for (left in candidates) {
                if (left in used) continue
                val right = candidates.asSequence()
                    .filter { it > left && it !in used }
                    .filter { abs((it - left) - bestGap) <= tolerance }
                    .minByOrNull { abs((it - left) - bestGap) }
                if (right != null) {
                    used.add(left)
                    used.add(right)
                    pairs++
                }
            }
            val possiblePairs = candidates.size / 2f
            val confidence = if (possiblePairs <= 0f) 0f else
                (pairs / possiblePairs).coerceIn(0f, 1f)
            return Result(pairs.coerceAtMost(300), candidates.size, bestGap, confidence)
        } finally {
            image.recycle()
        }
    }

    private fun luminance(color: Int): Double =
        (Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114)
}
