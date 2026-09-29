package io.github.rmdodhia.gesturelauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RecognizerTest {
    private val rnd = Random(7)
    private val th = Settings.DEFAULT_THRESHOLD

    private fun g(name: String, vararg samples: GestureSample) = Gesture(name, name, samples.toList(), LaunchApp("pkg.$name", name))
    private fun shape(name: String, fingers: Int = 1) = Synthetic.sample(name, rnd, fingers = fingers)
    private fun recognizedName(r: Recognition) = (r as? Recognition.Recognized)?.match?.gesture?.name

    @Test
    fun threeFingerTapFeatures() {
        val f = FeatureExtractor.analyze(Synthetic.tap(listOf(3), emptyList()))
        assertEquals(Kind.TAP, f.kind)
        assertEquals(3, f.fingerCount)
        assertEquals(listOf(3), f.tapGroups)
    }

    @Test
    fun doubleTapIsTwoGroups() {
        val f = FeatureExtractor.analyze(Synthetic.tap(listOf(1, 1), listOf(200)))
        assertEquals(listOf(1, 1), f.tapGroups)
        assertEquals(1, f.fingerCount)
    }

    @Test
    fun movingTrackIsMotion() {
        val f = FeatureExtractor.analyze(shape("circle"))
        assertEquals(Kind.MOTION, f.kind)
        assertEquals(1, f.strokeCount)
    }

    @Test
    fun longPressIsNotTap() {
        val s = GestureSample(listOf(Track(listOf(TouchPoint(0f, 0f, 0), TouchPoint(1f, 1f, 900)))), 3f)
        assertEquals(Kind.MOTION, FeatureExtractor.analyze(s).kind)
        // And recognizing it must not crash (zero-length path).
        Recognizer.recognize(s, listOf(g("circle", shape("circle"))), th)
    }

    @Test
    fun tapGesturesDistinguishedByFingerCount() {
        val gs = listOf(
            g("tap3", Synthetic.tap(listOf(3), emptyList())),
            g("tap2", Synthetic.tap(listOf(2), emptyList())),
            g("double", Synthetic.tap(listOf(1, 1), listOf(180))),
        )
        assertEquals("tap3", recognizedName(Recognizer.recognize(Synthetic.tap(listOf(3), emptyList()), gs, th)))
        assertEquals("tap2", recognizedName(Recognizer.recognize(Synthetic.tap(listOf(2), emptyList()), gs, th)))
        assertEquals("double", recognizedName(Recognizer.recognize(Synthetic.tap(listOf(1, 1), listOf(250)), gs, th)))
        assertTrue(Recognizer.recognize(Synthetic.tap(listOf(4), emptyList()), gs, th) is Recognition.NotRecognized)
    }

    @Test
    fun tapRhythmsDistinguished() {
        // "short-short-long" vs "long-short-short"
        val gs = listOf(
            g("ssl", Synthetic.tap(listOf(1, 1, 1, 1), listOf(150, 150, 450))),
            g("lss", Synthetic.tap(listOf(1, 1, 1, 1), listOf(450, 150, 150))),
        )
        assertEquals("ssl", recognizedName(Recognizer.recognize(Synthetic.tap(listOf(1, 1, 1, 1), listOf(170, 140, 480)), gs, th)))
        assertEquals("lss", recognizedName(Recognizer.recognize(Synthetic.tap(listOf(1, 1, 1, 1), listOf(400, 160, 170)), gs, th)))
        // Same rhythm at a different tempo still matches.
        assertEquals("ssl", recognizedName(Recognizer.recognize(Synthetic.tap(listOf(1, 1, 1, 1), listOf(100, 100, 300)), gs, th)))
    }

    @Test
    fun swipeDirectionMatters() {
        val gs = listOf(
            g("right", shape("swipe_right"), shape("swipe_right")),
            g("left", shape("swipe_left"), shape("swipe_left")),
            g("up", shape("swipe_up"), shape("swipe_up")),
        )
        repeat(10) {
            assertEquals("right", recognizedName(Recognizer.recognize(shape("swipe_right"), gs, th)))
            assertEquals("left", recognizedName(Recognizer.recognize(shape("swipe_left"), gs, th)))
            assertEquals("up", recognizedName(Recognizer.recognize(shape("swipe_up"), gs, th)))
        }
    }

    @Test
    fun multiFingerDirectionsMatchOneToOne() {
        // Three simultaneous horizontal strokes; only the directions differ, so the point clouds are identical.
        fun swipes(vararg rightward: Boolean) = GestureSample(
            rightward.mapIndexed { i, r ->
                Track((0..20).map { k -> TouchPoint(if (r) k * 15f else 300f - k * 15f, i * 100f, k * 10L) })
            },
        )
        val twoRightOneLeft = Recognizer.Prepared(swipes(true, true, false))
        val oneRightTwoLeft = Recognizer.Prepared(swipes(true, false, false))
        assertEquals(0f, Recognizer.score(twoRightOneLeft, oneRightTwoLeft), 0f)
        assertTrue(Recognizer.score(twoRightOneLeft, Recognizer.Prepared(swipes(false, true, true))) > 0.9f)
    }

    @Test
    fun fingerCountSeparatesSwipes() {
        val gs = listOf(
            g("one", shape("swipe_right"), shape("swipe_right")),
            g("two", shape("swipe_right", fingers = 2), shape("swipe_right", fingers = 2)),
            g("three", shape("swipe_right", fingers = 3), shape("swipe_right", fingers = 3)),
        )
        assertEquals(2, FeatureExtractor.analyze(shape("swipe_right", fingers = 2)).fingerCount)
        repeat(5) {
            assertEquals("one", recognizedName(Recognizer.recognize(shape("swipe_right"), gs, th)))
            assertEquals("two", recognizedName(Recognizer.recognize(shape("swipe_right", fingers = 2), gs, th)))
            assertEquals("three", recognizedName(Recognizer.recognize(shape("swipe_right", fingers = 3), gs, th)))
        }
    }

    @Test
    fun multiStrokeShapeIgnoresStrokeOrderAndDirection() {
        val gs = listOf(g("x", shape("x"), shape("x")), g("plus", shape("plus"), shape("plus")))
        val x = shape("x")
        // Draw the strokes in the opposite order, each reversed.
        val reversed = GestureSample(
            x.tracks.reversed().mapIndexed { i, t ->
                Track(t.points.reversed().mapIndexed { j, p -> p.copy(t = i * 1000L + j * 8L) })
            },
            x.density,
        )
        assertEquals("x", recognizedName(Recognizer.recognize(reversed, gs, th)))
    }

    @Test
    fun ambiguousGesturesAreRejectedNotGuessed() {
        val same = shape("circle")
        val gs = listOf(g("a", same), g("b", same))
        val r = Recognizer.recognize(shape("circle"), gs, 0.1f)
        assertTrue(r is Recognition.NotRecognized && r.ambiguousWith != null)
    }

    @Test
    fun emptyAndNoGestures() {
        assertEquals(Recognition.Empty, Recognizer.recognize(GestureSample(emptyList()), emptyList(), th))
        assertEquals(Recognition.NotRecognized(null), Recognizer.recognize(shape("circle"), emptyList(), th))
        // Gestures without samples are ignored rather than crashing.
        assertEquals(Recognition.NotRecognized(null), Recognizer.recognize(shape("circle"), listOf(g("empty")), th))
    }

    @Test
    fun similarityWarning() {
        val existing = listOf(g("circle", shape("circle"), shape("circle")), g("z", shape("z")))
        val sim = Recognizer.similarGestures(listOf(shape("circle")), existing, th)
        assertEquals(listOf("circle"), sim.map { it.gesture.name })
        assertTrue(Recognizer.similarGestures(listOf(shape("triangle")), listOf(existing[1]), th).isEmpty())
    }

    @Test
    fun resampleAlwaysReturnsN() {
        repeat(200) {
            val s = if (it % 2 == 0) Synthetic.scribble(rnd) else shape(Synthetic.shapes.keys.random(rnd))
            assertEquals(PointCloud.N, PointCloud.normalize(s.tracks).size)
        }
        // Degenerate: single point, duplicate points.
        assertEquals(PointCloud.N, PointCloud.normalize(listOf(Track(listOf(TouchPoint(1f, 1f, 0))))).size)
        assertEquals(PointCloud.N, PointCloud.normalize(listOf(Track(List(5) { TouchPoint(1f, 1f, it.toLong()) })) ).size)
    }
}
