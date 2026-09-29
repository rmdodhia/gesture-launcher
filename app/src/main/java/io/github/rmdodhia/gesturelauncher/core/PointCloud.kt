package io.github.rmdodhia.gesturelauncher.core

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * $P point-cloud recognizer (Vatavu, Anthony & Wobbrock, 2012).
 * Treats a gesture as an unordered cloud of points, so it handles multi-stroke shapes
 * regardless of stroke order or direction.
 */
object PointCloud {
    const val N = 32

    data class P(val x: Float, val y: Float, val stroke: Int)

    /** Converts tracks to a normalized cloud of exactly [N] points. */
    fun normalize(tracks: List<Track>): List<P> {
        val pts = ArrayList<P>()
        tracks.forEachIndexed { i, t -> t.points.forEach { pts += P(it.x, it.y, i) } }
        require(pts.isNotEmpty()) { "Cannot normalize an empty gesture" }
        return translateToOrigin(scale(resample(pts, N)))
    }

    fun resample(points: List<P>, n: Int): List<P> {
        val length = pathLength(points)
        if (length <= 1e-6f) {
            val c = centroid(points)
            return List(n) { c }
        }
        val interval = length / (n - 1)
        var d = 0f
        val src = ArrayList(points)
        val out = ArrayList<P>(n)
        out += src[0]
        var i = 1
        while (i < src.size) {
            val a = src[i - 1]
            val b = src[i]
            if (a.stroke == b.stroke) {
                val seg = hypot(b.x - a.x, b.y - a.y)
                if (d + seg >= interval && seg > 0f) {
                    val r = (interval - d) / seg
                    val q = P(a.x + r * (b.x - a.x), a.y + r * (b.y - a.y), a.stroke)
                    out += q
                    src.add(i, q)
                    d = 0f
                    if (out.size == n) break
                } else {
                    d += seg
                }
            }
            i++
        }
        // Rounding can leave us one short; pad with the last point.
        while (out.size < n) out += src.last()
        return out
    }

    private fun pathLength(points: List<P>): Float {
        var d = 0f
        for (i in 1 until points.size) {
            if (points[i].stroke == points[i - 1].stroke) {
                d += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
            }
        }
        return d
    }

    private fun scale(points: List<P>): List<P> {
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val size = max(maxX - minX, maxY - minY).takeIf { it > 1e-6f } ?: 1f
        return points.map { P((it.x - minX) / size, (it.y - minY) / size, it.stroke) }
    }

    private fun centroid(points: List<P>): P =
        P(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat(), 0)

    private fun translateToOrigin(points: List<P>): List<P> {
        val c = centroid(points)
        return points.map { P(it.x - c.x, it.y - c.y, it.stroke) }
    }

    /** Greedy cloud match distance between two normalized clouds of equal size. Lower = more similar. */
    fun distance(a: List<P>, b: List<P>): Float {
        require(a.size == b.size) { "Clouds must have equal size" }
        val n = a.size
        val step = floor(n.toDouble().pow(0.5)).toInt().coerceAtLeast(1)
        var best = Float.MAX_VALUE
        var i = 0
        while (i < n) {
            best = min(best, min(cloudDistance(a, b, i), cloudDistance(b, a, i)))
            i += step
        }
        return best
    }

    private fun cloudDistance(a: List<P>, b: List<P>, start: Int): Float {
        val n = a.size
        val matched = BooleanArray(n)
        var sum = 0f
        var i = start
        do {
            var index = -1
            var minD = Float.MAX_VALUE
            for (j in 0 until n) {
                if (!matched[j]) {
                    val d = hypot(a[i].x - b[j].x, a[i].y - b[j].y)
                    if (d < minD) {
                        minD = d
                        index = j
                    }
                }
            }
            matched[index] = true
            val weight = 1f - ((i - start + n) % n).toFloat() / n
            sum += weight * minD
            i = (i + 1) % n
        } while (i != start)
        return sum
    }

    /** Maps a $P distance to a 0..1 similarity score. Calibrated in PointCloudCalibrationTest. */
    fun scoreFromDistance(d: Float): Float = (1f - d / SCORE_SCALE).coerceIn(0f, 1f)

    const val SCORE_SCALE = 6f
}
