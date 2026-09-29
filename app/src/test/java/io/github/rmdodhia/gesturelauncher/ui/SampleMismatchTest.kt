package io.github.rmdodhia.gesturelauncher.ui

import io.github.rmdodhia.gesturelauncher.core.GestureSample
import io.github.rmdodhia.gesturelauncher.core.TouchPoint
import io.github.rmdodhia.gesturelauncher.core.Track
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SampleMismatchTest {
    private fun tap(fingers: Int) = GestureSample(List(fingers) { i -> Track(listOf(TouchPoint(i * 100f, 0f, 0), TouchPoint(i * 100f, 0f, 80))) })
    private val line = GestureSample(listOf(Track((0..20).map { TouchPoint(it * 20f, 0f, it * 10L) })))

    @Test
    fun sameKindIsAccepted() {
        assertNull(mismatch(emptyList()))
        assertNull(mismatch(listOf(tap(3), tap(3))))
        assertNull(mismatch(listOf(line, line)))
    }

    @Test
    fun differentKindIsRejected() {
        assertNotNull(mismatch(listOf(line, tap(1))))
        assertNotNull(mismatch(listOf(tap(3), tap(2))))
    }
}
