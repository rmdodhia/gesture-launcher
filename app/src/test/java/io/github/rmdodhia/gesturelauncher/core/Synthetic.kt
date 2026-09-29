package io.github.rmdodhia.gesturelauncher.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** Generates synthetic, human-like gesture samples for tests. Shapes are defined in a unit box. */
object Synthetic {
    /** A shape = list of strokes; each stroke is a polyline of unit-box points. */
    val shapes: Map<String, List<List<Pair<Float, Float>>>> = mapOf(
        "circle" to listOf((0..40).map { i -> val a = 2 * PI * i / 40; (0.5f + 0.5f * cos(a).toFloat()) to (0.5f + 0.5f * sin(a).toFloat()) }),
        "square" to listOf(listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f, 0f to 0f)),
        "triangle" to listOf(listOf(0.5f to 0f, 1f to 1f, 0f to 1f, 0.5f to 0f)),
        "x" to listOf(listOf(0f to 0f, 1f to 1f), listOf(1f to 0f, 0f to 1f)),
        "check" to listOf(listOf(0f to 0.6f, 0.35f to 1f, 1f to 0f)),
        "z" to listOf(listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)),
        "l" to listOf(listOf(0f to 0f, 0f to 1f, 0.6f to 1f)),
        "v" to listOf(listOf(0f to 0f, 0.5f to 1f, 1f to 0f)),
        "caret" to listOf(listOf(0f to 1f, 0.5f to 0f, 1f to 1f)),
        "zigzag" to listOf(listOf(0f to 0.5f, 0.25f to 0f, 0.5f to 1f, 0.75f to 0f, 1f to 0.5f)),
        "plus" to listOf(listOf(0.5f to 0f, 0.5f to 1f), listOf(0f to 0.5f, 1f to 0.5f)),
        "s" to listOf(listOf(1f to 0.1f, 0.5f to 0f, 0f to 0.2f, 0.1f to 0.45f, 0.5f to 0.5f, 0.9f to 0.55f, 1f to 0.8f, 0.5f to 1f, 0f to 0.9f)),
        "swipe_right" to listOf(listOf(0f to 0.5f, 1f to 0.5f)),
        "swipe_left" to listOf(listOf(1f to 0.5f, 0f to 0.5f)),
        "swipe_up" to listOf(listOf(0.5f to 1f, 0.5f to 0f)),
        "heart" to listOf((0..60).map { i ->
            val t = 2 * PI * i / 60
            val x = 16 * sin(t).let { it * it * it }
            val y = 13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)
            ((x / 34 + 0.5).toFloat()) to ((-y / 34 + 0.5).toFloat())
        }),
    )

    data class Variation(
        val jitter: Float = 0.004f,
        val rotationDeg: Float = 10f,
        val aspect: Float = 0.15f,
        val wobble: Float = 0.05f,
    )

    fun sample(
        shape: String,
        rnd: Random,
        v: Variation = Variation(),
        fingers: Int = 1,
        density: Float = 3f,
    ): GestureSample {
        val strokes = shapes.getValue(shape)
        val size = 300f + rnd.nextFloat() * 500f
        val sx = size * (1 + (rnd.nextFloat() * 2 - 1) * v.aspect)
        val sy = size * (1 + (rnd.nextFloat() * 2 - 1) * v.aspect)
        val rot = Math.toRadians(((rnd.nextFloat() * 2 - 1) * v.rotationDeg).toDouble())
        val ox = 100f + rnd.nextFloat() * 300f
        val oy = 300f + rnd.nextFloat() * 800f
        val wobblePhase = rnd.nextFloat() * 6.28f
        var t = 0L
        val tracks = mutableListOf<Track>()
        for (stroke in strokes) {
            val fingerTracks = List(fingers) { mutableListOf<TouchPoint>() }
            val strokeStart = t
            val dense = densify(stroke, 0.01f + rnd.nextFloat() * 0.02f)
            dense.forEachIndexed { i, (ux, uy) ->
                val w = v.wobble * sin(i * 0.15f + wobblePhase)
                val jx = ux - 0.5f + w + gauss(rnd) * v.jitter
                val jy = uy - 0.5f + w * 0.7f + gauss(rnd) * v.jitter
                val x = (jx * sx * cos(rot) - jy * sy * sin(rot)).toFloat() + ox + sx / 2
                val y = (jx * sx * sin(rot) + jy * sy * cos(rot)).toFloat() + oy + sy / 2
                for (f in 0 until fingers) fingerTracks[f] += TouchPoint(x + f * 150f, y + f * 20f, t + f * 5)
                t += 8
            }
            if (t == strokeStart) t += 8
            fingerTracks.forEach { tracks += Track(it) }
            t += 250 + rnd.nextLong(200)
        }
        return GestureSample(tracks, density)
    }

    /** Random scribble: a smooth random walk. Used to measure false triggers. */
    fun scribble(rnd: Random, density: Float = 3f): GestureSample {
        var x = 500f
        var y = 1000f
        var heading = rnd.nextFloat() * 6.28f
        val n = 40 + rnd.nextInt(120)
        val pts = (0 until n).map { i ->
            heading += (rnd.nextFloat() * 2 - 1) * 0.6f
            x += cos(heading) * 12
            y += sin(heading) * 12
            TouchPoint(x, y, i * 8L)
        }
        return GestureSample(listOf(Track(pts)), density)
    }

    fun tap(fingers: List<Int>, gapsMs: List<Long>, density: Float = 3f): GestureSample {
        require(gapsMs.size == fingers.size - 1)
        var t = 0L
        val tracks = mutableListOf<Track>()
        fingers.forEachIndexed { i, n ->
            repeat(n) { f ->
                val x = 300f + f * 150f
                tracks += Track(listOf(TouchPoint(x, 800f, t + f * 10), TouchPoint(x + 2, 801f, t + f * 10 + 80)))
            }
            if (i < gapsMs.size) t += gapsMs[i]
        }
        return GestureSample(tracks, density)
    }

    private fun densify(stroke: List<Pair<Float, Float>>, step: Float): List<Pair<Float, Float>> {
        if (stroke.size == 1) return stroke
        val out = mutableListOf(stroke[0])
        for (i in 1 until stroke.size) {
            val (ax, ay) = stroke[i - 1]
            val (bx, by) = stroke[i]
            val n = (hypot(bx - ax, by - ay) / step).toInt().coerceAtLeast(1)
            for (k in 1..n) out += (ax + (bx - ax) * k / n) to (ay + (by - ay) * k / n)
        }
        return out
    }

    private fun gauss(rnd: Random): Float {
        // Box-Muller
        val u1 = rnd.nextDouble().coerceAtLeast(1e-9)
        val u2 = rnd.nextDouble()
        return (kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * cos(2 * PI * u2)).toFloat()
    }
}
