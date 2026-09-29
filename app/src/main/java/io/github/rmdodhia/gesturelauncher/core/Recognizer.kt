package io.github.rmdodhia.gesturelauncher.core

import kotlin.math.abs
import kotlin.math.hypot

data class Match(val gesture: Gesture, val score: Float)

sealed interface Recognition {
    data object Empty : Recognition

    data class Recognized(val match: Match, val runnerUp: Match?) : Recognition

    /** [best] is the closest gesture (if any), for debugging/tuning. */
    data class NotRecognized(val best: Match?, val ambiguousWith: Match? = null) : Recognition
}

object Recognizer {
    /** The winner must beat the runner-up (a different gesture) by at least this much. */
    const val MARGIN = 0.03f

    /** A stroke is "directional" if its start-to-end distance is at least this fraction of the gesture size. */
    private const val DIRECTIONAL = 0.5f
    private const val DIRECTION_PARTNER = 0.2f
    private const val MIN_DIRECTION_COS = 0.5f

    /** Pre-processed sample so each template is normalized only once per recognition. */
    class Prepared(val sample: GestureSample) {
        val features = FeatureExtractor.analyze(sample)
        val cloud: List<PointCloud.P>? by lazy {
            if (features.kind == Kind.MOTION) PointCloud.normalize(sample.tracks.filter { it.points.isNotEmpty() }) else null
        }
        val directions: List<Pair<Float, Float>> by lazy { strokeDirections(sample) }
    }

    fun recognize(sample: GestureSample, gestures: List<Gesture>, threshold: Float): Recognition {
        if (sample.isEmpty) return Recognition.Empty
        val candidate = Prepared(sample)
        val ranked = gestures
            .mapNotNull { g -> bestScore(candidate, g)?.let { Match(g, it) } }
            .sortedByDescending { it.score }
        val best = ranked.firstOrNull() ?: return Recognition.NotRecognized(null)
        val runnerUp = ranked.getOrNull(1)
        if (best.score < threshold) return Recognition.NotRecognized(best)
        if (runnerUp != null && runnerUp.score > best.score - MARGIN) {
            return Recognition.NotRecognized(best, ambiguousWith = runnerUp)
        }
        return Recognition.Recognized(best, runnerUp)
    }

    /** Other gestures that [samples] would be confused with (score >= threshold). */
    fun similarGestures(samples: List<GestureSample>, others: List<Gesture>, threshold: Float): List<Match> =
        others.mapNotNull { g ->
            val s = samples.filter { !it.isEmpty }.maxOfOrNull { bestScore(Prepared(it), g) ?: 0f } ?: 0f
            if (s >= threshold) Match(g, s) else null
        }.sortedByDescending { it.score }

    private fun bestScore(candidate: Prepared, gesture: Gesture): Float? =
        gesture.samples.filter { !it.isEmpty }.maxOfOrNull { score(candidate, Prepared(it)) }

    /** Similarity in 0..1 between a candidate and a single template sample. */
    fun score(a: Prepared, b: Prepared): Float {
        val fa = a.features
        val fb = b.features
        if (fa.kind != fb.kind) return 0f
        return when (fa.kind) {
            Kind.TAP -> tapScore(fa, fb)
            Kind.MOTION -> {
                if (fa.fingerCount != fb.fingerCount || fa.strokeCount != fb.strokeCount) return 0f
                // Direction matters for strokes drawn together (single strokes, multi-finger swipes) so that
                // left/right swipes differ. Multi-stroke drawings (an X) stay order- and direction-free.
                val simultaneous = fa.strokeCount == fa.fingerCount
                if (simultaneous && !directionsCompatible(a.directions, b.directions)) return 0f
                PointCloud.scoreFromDistance(PointCloud.distance(a.cloud!!, b.cloud!!))
            }
        }
    }

    private fun tapScore(a: Features, b: Features): Float {
        if (a.tapGroups != b.tapGroups) return 0f
        if (a.tapGroupStarts.size < 3) return 1f
        val ia = normalizedIntervals(a.tapGroupStarts)
        val ib = normalizedIntervals(b.tapGroupStarts)
        val maxDiff = ia.zip(ib).maxOf { (x, y) -> abs(x - y) }
        return (1f - maxDiff).coerceIn(0f, 1f)
    }

    /** Gaps between taps as fractions of the whole tap sequence, so rhythm is tempo-independent. */
    private fun normalizedIntervals(starts: List<Long>): List<Float> {
        val gaps = starts.zipWithNext { x, y -> (y - x).toFloat() }
        val total = gaps.sum().takeIf { it > 0f } ?: return gaps.map { 0f }
        return gaps.map { it / total }
    }

    /**
     * Unit start-to-end vectors of each stroke, scaled by stroke displacement relative to gesture size
     * (so length encodes how line-like the stroke is). $P ignores direction, so a left swipe and a right
     * swipe look identical to it; this check tells them apart. Closed shapes (circles) have short vectors
     * and are exempt.
     */
    private fun strokeDirections(sample: GestureSample): List<Pair<Float, Float>> {
        val pts = sample.tracks.flatMap { it.points }
        if (pts.isEmpty()) return emptyList()
        val size = maxOf(pts.maxOf { it.x } - pts.minOf { it.x }, pts.maxOf { it.y } - pts.minOf { it.y })
        if (size <= 1e-6f) return emptyList()
        return sample.tracks.filter { it.points.size >= 2 }.map {
            val dx = it.points.last().x - it.points.first().x
            val dy = it.points.last().y - it.points.first().y
            dx / size to dy / size
        }
    }

    private fun directionsCompatible(a: List<Pair<Float, Float>>, b: List<Pair<Float, Float>>): Boolean =
        eachDirectionalHasPartner(a, b) && eachDirectionalHasPartner(b, a)

    /**
     * Every directional stroke in [from] needs its own (one-to-one) compatible stroke in [to], so that
     * e.g. "two fingers right, one left" doesn't match "one right, two left".
     */
    private fun eachDirectionalHasPartner(from: List<Pair<Float, Float>>, to: List<Pair<Float, Float>>): Boolean {
        val directional = from.filter { (x, y) -> hypot(x, y) >= DIRECTIONAL }
        val compatible = directional.map { (x, y) ->
            val len = hypot(x, y)
            to.indices.filter { j ->
                val (u, v) = to[j]
                val l2 = hypot(u, v)
                l2 >= DIRECTION_PARTNER && (x * u + y * v) / (len * l2) >= MIN_DIRECTION_COS
            }
        }
        // Bipartite matching (Kuhn's algorithm); sizes are tiny (number of fingers).
        val owner = IntArray(to.size) { -1 }
        fun assign(i: Int, seen: BooleanArray): Boolean {
            for (j in compatible[i]) {
                if (seen[j]) continue
                seen[j] = true
                if (owner[j] == -1 || assign(owner[j], seen)) {
                    owner[j] = i
                    return true
                }
            }
            return false
        }
        return directional.indices.all { assign(it, BooleanArray(to.size)) }
    }
}
