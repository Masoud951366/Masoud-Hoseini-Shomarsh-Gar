package com.masoud.glasscounter

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Experimental track-based boundary detector.
 *
 * Unlike the previous implementation, this does not count every strong
 * vertical edge. It builds edge candidates independently in image bands,
 * links candidates that continue through the stack height (allowing slight
 * slant/offset), and counts intervals between consecutive accepted tracks.
 *
 * This is deliberately NOT wired into the production UI until labelled-photo
 * validation passes. Reflections that form long, continuous vertical lines
 * remain an ambiguity for a single RGB image.
 */
internal object GlassBoundaryTrackEngine {
    data class Result(
        val count: Int,
        val boundaryTracks: Int,
        val confidence: Float,
        val reliable: Boolean,
        val diagnostic: String
    )

    private data class Candidate(
        val x: Int,
        val strength: Float,
        val polarity: Int
    )

    private data class Track(
        val positions: MutableList<Int> = mutableListOf(),
        val strengths: MutableList<Float> = mutableListOf(),
        val polarities: MutableList<Int> = mutableListOf(),
        var support: Int = 0
    ) {
        val center: Int get() = positions.sorted()[positions.size / 2]
        val medianStrength: Float get() = strengths.sorted()[strengths.size / 2]
        val polarityAgreement: Float
            get() {
                val nonZero = polarities.filter { it != 0 }
                if (nonZero.isEmpty()) return 0f
                val positive = nonZero.count { it > 0 }
                return max(positive, nonZero.size - positive).toFloat() / nonZero.size
            }
    }

    private const val MAX_WIDTH = 1600
    private const val BAND_COUNT = 11
    private const val MIN_SUPPORT_RATIO = 0.55f

    fun analyze(source: Bitmap): Result {
        if (source.width < 120 || source.height < 160) {
            return failure("ابعاد تصویر برای ردیابی مرزها کافی نیست")
        }

        val width = min(MAX_WIDTH, source.width)
        val height = max(160, source.height * width / source.width)
        val image = if (width == source.width && height == source.height) source
            else Bitmap.createScaledBitmap(source, width, height, true)

        try {
            val yTop = height / 14
            val yBottom = height * 13 / 14
            val bandHeight = max(8, (yBottom - yTop) / BAND_COUNT)
            val mergeRadius = max(2, width / 700)
            val maxDrift = max(5, width / 35)
            val bands = ArrayList<List<Candidate>>(BAND_COUNT)

            for (band in 0 until BAND_COUNT) {
                val y0 = yTop + band * bandHeight
                val y1 = if (band == BAND_COUNT - 1) yBottom else min(yBottom, y0 + bandHeight)
                bands.add(detectCandidates(image, y0, y1, mergeRadius))
            }

            val tracks = ArrayList<Track>()
            for ((bandIndex, candidates) in bands.withIndex()) {
                val unused = candidates.toMutableList()
                // Extend the closest existing track at most once per band.
                for (track in tracks) {
                    val expected = if (track.positions.size >= 2) {
                        val n = track.positions.size
                        track.positions[n - 1] + (track.positions[n - 1] - track.positions[n - 2])
                    } else track.center
                    val best = unused.minByOrNull { abs(it.x - expected) } ?: continue
                    val tolerance = min(maxDrift, max(mergeRadius * 3, width / 90))
                    if (abs(best.x - expected) <= tolerance) {
                        track.positions.add(best.x)
                        track.strengths.add(best.strength)
                        track.polarities.add(best.polarity)
                        track.support++
                        unused.remove(best)
                    }
                }
                // New tracks can start in any band; short tracks will be rejected.
                for (candidate in unused) {
                    tracks.add(Track(
                        positions = mutableListOf(candidate.x),
                        strengths = mutableListOf(candidate.strength),
                        polarities = mutableListOf(candidate.polarity),
                        support = 1
                    ))
                }
            }

            val minSupport = (BAND_COUNT * MIN_SUPPORT_RATIO).roundToInt()
            val accepted = tracks
                .filter { it.support >= minSupport && it.polarityAgreement >= 0.60f }
                .sortedBy { it.center }

            // Collapse duplicate tracks whose median positions are nearly identical.
            val unique = ArrayList<Track>()
            for (track in accepted) {
                val previous = unique.lastOrNull()
                if (previous == null || abs(track.center - previous.center) > mergeRadius * 3) {
                    unique.add(track)
                } else if (track.medianStrength > previous.medianStrength) {
                    unique[unique.lastIndex] = track
                }
            }

            if (unique.size < 3) {
                return Result(0, unique.size, 0f, false,
                    "مرزهای پیوسته کافی پیدا نشد؛ تصویر نیازمند ثبت بهتر یا بررسی است")
            }

            // Count is the number of consecutive spaces between boundary tracks.
            val count = unique.size - 1
            val supportScore = unique.map { it.support.toFloat() / BAND_COUNT }.average().toFloat()
            val polarityScore = unique.map { it.polarityAgreement }.average().toFloat()
            val gapList = unique.zipWithNext { a, b -> b.center - a.center }
            val positiveGaps = gapList.filter { it > mergeRadius * 3 }
            val gapCoverage = positiveGaps.size.toFloat() / max(1, gapList.size)
            val confidence = (0.55f * supportScore + 0.25f * polarityScore +
                0.20f * gapCoverage).coerceIn(0f, 1f)

            // A single image cannot reliably distinguish a persistent reflection
            // from a true glass boundary. Do not label such results industrially reliable.
            val reliable = confidence >= 0.86f && unique.size >= 4
            val diagnostic = if (reliable)
                "ردیابی مرزها پایدار است؛ همچنان باید با تعداد مرجع کارخانه اعتبارسنجی شود"
            else
                "احتمال بازتاب یا مرز کاذب وجود دارد؛ عدد قطعی تلقی نشود"

            return Result(count.coerceAtMost(300), unique.size, confidence, reliable, diagnostic)
        } finally {
            if (image !== source) image.recycle()
        }
    }

    private fun detectCandidates(
        image: Bitmap,
        y0: Int,
        y1: Int,
        mergeRadius: Int
    ): List<Candidate> {
        val width = image.width
        val response = FloatArray(width)
        val signed = FloatArray(width)
        val stride = max(1, (y1 - y0) / 220)

        for (x in 2 until width - 2) {
            var magnitude = 0f
            var direction = 0f
            var samples = 0
            var y = y0
            while (y < y1) {
                val left = luminance(image.getPixel(x - 2, y))
                val right = luminance(image.getPixel(x + 2, y))
                val delta = right - left
                magnitude += abs(delta)
                direction += delta
                samples++
                y += stride
            }
            if (samples > 0) {
                response[x] = magnitude / samples
                signed[x] = direction / samples
            }
        }

        val sorted = response.sliceArray(3 until width - 3).sorted()
        if (sorted.isEmpty()) return emptyList()
        val median = sorted[sorted.size / 2]
        val p78 = sorted[(sorted.size * 78 / 100).coerceIn(0, sorted.lastIndex)]
        val deviations = sorted.map { abs(it - median) }.sorted()
        val mad = deviations[deviations.size / 2]
        val threshold = max(p78, median + max(2.0f, mad * 1.7f))

        val peaks = (3 until width - 3).filter { x ->
            response[x] >= threshold &&
                response[x] >= response[x - 1] &&
                response[x] > response[x + 1]
        }.sortedByDescending { response[it] }

        val selected = ArrayList<Candidate>()
        for (x in peaks) {
            if (selected.none { abs(it.x - x) <= mergeRadius }) {
                val polarity = when {
                    signed[x] > 0.8f -> 1
                    signed[x] < -0.8f -> -1
                    else -> 0
                }
                selected.add(Candidate(x, response[x], polarity))
            }
        }
        return selected.sortedBy { it.x }
    }

    private fun luminance(color: Int): Float =
        Color.red(color) * 0.299f + Color.green(color) * 0.587f + Color.blue(color) * 0.114f

    private fun failure(message: String) =
        Result(0, 0, 0f, false, message)
}
