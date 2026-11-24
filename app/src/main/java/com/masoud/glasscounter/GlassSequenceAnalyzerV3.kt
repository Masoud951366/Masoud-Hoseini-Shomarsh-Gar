package com.masoud.glasscounter

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Experimental V3 sequence-lattice analyzer.
 *
 * Unlike the V1/V2 peak counter, it does not equate every detected edge with a glass boundary.
 * It searches for a repeated interval (pitch) shared by independent horizontal image bands,
 * then evaluates a phase-locked sequence of boundaries. This is a candidate generator only;
 * reflections can also be periodic and must be rejected by reference-image validation.
 */
internal object GlassSequenceAnalyzerV3 {
    data class Result(
        val estimatedPanels: Int,
        val boundaryRows: List<Int>,
        val pitchPx: Float,
        val confidence: Float,
        val diagnostic: String
    )

    private const val BANDS =  nine
    private const val MAX_WIDTH = 1000

    fun analyze(bitmap: Bitmap): Result {
        if (bitmap.width < 120 || bitmap.height < 160) return fail("تصویر برای تحلیل کوچک است.")
        val w = min(MAX_WIDTH, bitmap.width)
        val h = max(160, bitmap.height * w / bitmap.width)
        val image = if (w == bitmap.width && h == bitmap.height) bitmap
            else Bitmap.createScaledBitmap(bitmap, w, h, true)
        try {
            val y0 = h / 20
            val y1 = h * 19 / 20
            val roi = y1 - y0
            if (roi < 120) return fail("ناحیه قابل تحلیل کافی نیست.")

            // Build independent edge-energy profiles. Horizontal reflections that occur in
            // only one area should be weakened by the cross-band median.
            val profiles = ArrayList<FloatArray>(BANDS)
            for (band in 0 until BANDS) {
                val x0 = w / 20 + band * (w * 9 / 10) / BANDS
                val x1 = w / 20 + (band + 1) * (w * 9 / 10) / BANDS
                val profile = FloatArray(roi)
                val step = max(1, (x1 - x0) / 80)
                for (i in 2 until roi - 2) {
                    var sum = 0f
                    var n = 0
                    var x = x0
                    while (x < x1) {
                        val upper = luminance(image.getPixel(x, y0 + i - 2))
                        val lower = luminance(image.getPixel(x, y0 + i + 2))
                        sum += abs(lower - upper)
                        n++
                        x += step
                    }
                    profile[i] = if (n == 0) 0f else sum / n
                }
                profiles.add(normalize(profile))
            }

            val consensus = FloatArray(roi) { row ->
                val values = profiles.map { it[row] }.sorted()
                values[values.size / 2]
            }
            smoothInPlace(consensus)

            // Physical count target is 50..200 panels. Search a broad pitch range derived
            // from ROI height, not from an assumed millimetres-to-pixels conversion.
            val minPitch = max(2, roi / 220)
            val maxPitch = max(minPitch + 1, roi / 45)
            var bestPitch = 0
            var bestPhase = 0
            var bestScore = Float.NEGATIVE_INFINITY
            var secondScore = Float.NEGATIVE_INFINITY

            // First demand true periodic similarity at a candidate lag. A mere set of
            // strong unrelated reflections should not win just because some rows are bright.
            for (pitch in minPitch..maxPitch) {
                val periodicity = autocorrelation(consensus, pitch)
                if (periodicity <= 0.04f) continue
                var phase = 0
                var phaseScore = Float.NEGATIVE_INFINITY
                for (candidatePhase in 0 until pitch) {
                    var sum = 0f
                    var n = 0
                    var y = candidatePhase
                    while (y < roi) {
                        sum += consensus[y]
                        n++
                        y += pitch
                    }
                    if (n < 8) continue
                    val score = sum / n
                    if (score > phaseScore) {
                        phaseScore = score
                        phase = candidatePhase
                    }
                }
                if (phaseScore.isFinite()) {
                    val score = periodicity * 0.7f + phaseScore * 0.3f
                    if (score > bestScore) {
                        secondScore = bestScore
                        bestScore = score
                        bestPitch = pitch
                        bestPhase = phase
                    } else if (score > secondScore) secondScore = score
                }
            }
            if (bestPitch == 0) return fail("الگوی تکرارشونده قابل اتکا پیدا نشد.")
            val rows = ArrayList<Int>()
            var y = bestPhase
            while (y < roi) {
                if (consensus[y] >= 0.18f) rows.add(y0 + y)
                y += bestPitch
            }
            // A boundary sequence must span most of the stack; otherwise a local reflection
            // could masquerade as a complete count.
            if (rows.size < 8 || rows.last() - rows.first() < roi * 0.65f) {
                return Result(0, rows, bestPitch.toFloat(), 0f,
                    "پوشش مرزها در طول تصویر کافی نیست؛ شمارش رد شد.")
            }
            val separation = if (bestScore <= 0f) 0f
                else ((bestScore - max(0f, secondScore)) / bestScore).coerceIn(0f, 1f)
            val support = rows.size.toFloat() / max(1, roi / bestPitch)
            val confidence = (0.65f * support + 0.35f * separation).coerceIn(0f, 1f)
            val panels = (rows.size - 1).coerceIn(0, 300)
            return Result(panels, rows, bestPitch.toFloat(), confidence,
                "تخمین آزمایشی بر پایه تناوب؛ نیازمند اعتبارسنجی با شمارش مرجع.")
        } finally {
            if (image !== bitmap) image.recycle()
        }
    }
 
    private fun autocorrelation(values: FloatArray, lag: Int): Float {
        if (lag <= 0 || lag >= values.size / 2) return -1f
        val n = values.size - lag
        var meanA = 0f
        var meanB = 0f
        for (i in 0 until n) {
            meanA += values[i]
            meanB += values[i + lag]
        }
        meanA /= n
        meanB /= n
        var covariance = 0f
        var varianceA = 0f
        var varianceB = 0f
        for (i in 0 until n) {
            val a = values[i] - meanA
            val b = values[i + lag] - meanB
            covariance += a * b
            varianceA += a * a
            varianceB += b * b
        }
        val denominator = kotlin.math.sqrt(varianceA * varianceB)
        return if (denominator <= 1e-6f) -1f else covariance / denominator
    }

    private fun normalize(input: FloatArray): FloatArray {
        val sorted = input.sorted()
        val base = sorted[sorted.size / 2]
        val deviations = sorted.map { abs(it - base) }.sorted()
        val scale = max(1f, deviations[deviations.size / 2] * 3f)
        return FloatArray(input.size) { ((input[it] - base) / scale).coerceIn(0f, 1f) }
    }

    private fun smoothInPlace(values: FloatArray) {
        val copy = values.copyOf()
        for (i in 1 until values.lastIndex) {
            values[i] = (copy[i - 1] + 2f * copy[i] + copy[i + 1]) * 0.25f
        }
    }

    private fun luminance(color: Int): Float =
        Color.red(color) * 0.299f + Color.green(color) * 0.587f + Color.blue(color) * 0.114f

    private fun fail(message: String) = Result(0, emptyList(), 0f, 0f, message)

    private const val nine = 9
}
