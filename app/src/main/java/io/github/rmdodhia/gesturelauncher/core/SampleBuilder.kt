package io.github.rmdodhia.gesturelauncher.core

/**
 * Accumulates raw pointer events into a [GestureSample]. Pure Kotlin so it can be unit tested;
 * the Compose capture surface feeds it.
 */
class SampleBuilder(private val startTime: Long) {
    private val active = LinkedHashMap<Long, MutableList<TouchPoint>>()
    private val finished = mutableListOf<List<TouchPoint>>()

    val hasActivePointers: Boolean get() = active.isNotEmpty()
    val isEmpty: Boolean get() = active.isEmpty() && finished.isEmpty()

    private fun pt(x: Float, y: Float, t: Long) = TouchPoint(x, y, (t - startTime).coerceAtLeast(0))

    fun down(id: Long, x: Float, y: Float, t: Long) {
        // A down for an id that is still active means we missed its up; close the old track.
        active.remove(id)?.let { finished += it }
        active[id] = mutableListOf(pt(x, y, t))
    }

    fun move(id: Long, x: Float, y: Float, t: Long) {
        val track = active[id] ?: return
        val p = pt(x, y, t)
        val last = track.last()
        if (last.x != p.x || last.y != p.y) track += p
    }

    fun up(id: Long, x: Float, y: Float, t: Long) {
        val track = active.remove(id) ?: return
        val p = pt(x, y, t)
        val last = track.last()
        if (last.x != p.x || last.y != p.y || last.t != p.t) track += p
        finished += track
    }

    /** Ends any pointers that never reported an up (e.g. cancelled by the system). */
    fun cancelActive() {
        finished += active.values
        active.clear()
    }

    fun strokes(): List<List<TouchPoint>> = finished + active.values

    fun build(density: Float): GestureSample {
        val all = (finished + active.values).filter { it.isNotEmpty() }.sortedBy { it.first().t }
        return GestureSample(all.map { Track(it.toList()) }, density)
    }
}
