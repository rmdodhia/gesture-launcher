package io.github.rmdodhia.gesturelauncher.core

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Measures recognition quality on synthetic human-like variation, and guards the score calibration
 * ([PointCloud.SCORE_SCALE]) and default threshold against regressions.
 */
class PointCloudCalibrationTest {
    private val templatesPerGesture = 3
    private val trialsPerShape = 40

    private fun gestures(rnd: Random) = Synthetic.shapes.keys.map { name ->
        Gesture(name, name, List(templatesPerGesture) { Synthetic.sample(name, rnd) })
    }

    private fun pctl(xs: List<Float>, p: Double) = xs.sorted()[((xs.size - 1) * p).toInt()]

    @Test
    fun reportAndCheckAccuracy() {
        val rnd = Random(42)
        val gs = gestures(rnd)
        val threshold = Settings.DEFAULT_THRESHOLD

        val sameScores = mutableListOf<Float>()
        val otherScores = mutableListOf<Float>()
        var correct = 0
        var wrong = 0
        var rejected = 0
        for (g in gs) {
            repeat(trialsPerShape) {
                val cand = Recognizer.Prepared(Synthetic.sample(g.name, rnd))
                sameScores += g.samples.maxOf { Recognizer.score(cand, Recognizer.Prepared(it)) }
                otherScores += gs.filter { it !== g }.maxOf { o -> o.samples.maxOf { Recognizer.score(cand, Recognizer.Prepared(it)) } }
                when (val r = Recognizer.recognize(cand.sample, gs, threshold)) {
                    is Recognition.Recognized -> if (r.match.gesture.name == g.name) correct++ else {
                        wrong++
                        println("MISCLASSIFIED ${g.name} as ${r.match.gesture.name} (${r.match.score})")
                    }
                    else -> rejected++
                }
            }
        }
        var scribbleAccepted = 0
        val scribbleScores = mutableListOf<Float>()
        val scribbleTrials = 300
        repeat(scribbleTrials) {
            val s = Synthetic.scribble(rnd)
            val r = Recognizer.recognize(s, gs, threshold)
            if (r is Recognition.Recognized) scribbleAccepted++
            val best = when (r) {
                is Recognition.Recognized -> r.match.score
                is Recognition.NotRecognized -> r.best?.score ?: 0f
                Recognition.Empty -> 0f
            }
            scribbleScores += best
        }

        val total = gs.size * trialsPerShape
        println("same-shape best score: p05=${pctl(sameScores, .05)} p50=${pctl(sameScores, .5)}")
        println("best other-shape score: p50=${pctl(otherScores, .5)} p95=${pctl(otherScores, .95)} max=${otherScores.max()}")
        println("scribble best score: p50=${pctl(scribbleScores, .5)} p95=${pctl(scribbleScores, .95)}")
        println("correct=$correct wrong=$wrong rejected=$rejected of $total; scribbles accepted=$scribbleAccepted/$scribbleTrials")

        assertTrue("accuracy too low: $correct/$total", correct >= total * 0.93)
        assertTrue("too many misfires: $wrong/$total", wrong <= total * 0.01)
        // Random scribbles are sometimes close to a curvy shape; bounded, and tunable via the strictness setting.
        assertTrue("scribbles trigger actions: $scribbleAccepted", scribbleAccepted <= scribbleTrials * 0.15)
    }
}
