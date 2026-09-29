package io.github.rmdodhia.gesturelauncher.core

import kotlin.math.hypot

enum class Kind { TAP, MOTION }

data class Features(
    val kind: Kind,
    /** Max number of fingers down at the same time. */
    val fingerCount: Int,
    val strokeCount: Int,
    /** For TAP gestures: fingers in each successive tap, e.g. [3] = three-finger tap, [1, 1] = double tap. */
    val tapGroups: List<Int>,
    /** For TAP gestures: start time of each tap group (ms). */
    val tapGroupStarts: List<Long>,
)

object FeatureExtractor {
    const val TAP_SLOP_DP = 20f
    const val TAP_MAX_DURATION_MS = 350L

    /**
     * Contacts that overlap in time form one multi-finger tap. No slack: a quick double tap can have
     * < 40 ms between lifting and touching again, and must not be mistaken for a two-finger tap.
     */
    const val TAP_GROUP_SLACK_MS = 0L

    fun isTap(track: Track, density: Float): Boolean {
        if (track.points.isEmpty()) return true
        val slop = TAP_SLOP_DP * density
        val first = track.points.first()
        val maxMove = track.points.maxOf { hypot(it.x - first.x, it.y - first.y) }
        return maxMove <= slop && (track.end - track.start) <= TAP_MAX_DURATION_MS
    }

    fun analyze(sample: GestureSample): Features {
        val tracks = sample.tracks.filter { it.points.isNotEmpty() }.sortedBy { it.start }
        val allTaps = tracks.isNotEmpty() && tracks.all { isTap(it, sample.density) }
        val groups = mutableListOf<Int>()
        val groupStarts = mutableListOf<Long>()
        if (allTaps) {
            var groupEnd = Long.MIN_VALUE
            for (t in tracks) {
                if (groups.isNotEmpty() && t.start <= groupEnd + TAP_GROUP_SLACK_MS) {
                    groups[groups.lastIndex]++
                    groupEnd = maxOf(groupEnd, t.end)
                } else {
                    groups += 1
                    groupStarts += t.start
                    groupEnd = t.end
                }
            }
        }
        return Features(
            kind = if (allTaps) Kind.TAP else Kind.MOTION,
            fingerCount = maxSimultaneous(tracks),
            strokeCount = tracks.size,
            tapGroups = groups,
            tapGroupStarts = groupStarts,
        )
    }

    fun maxSimultaneous(tracks: List<Track>): Int {
        // Sweep line; at equal times process ends before starts so back-to-back touches don't overlap.
        val events = tracks.flatMap { listOf(it.start to 1, it.end to -1) }
            .sortedWith(compareBy<Pair<Long, Int>> { it.first }.thenBy { it.second })
        var cur = 0
        var max = 0
        for ((_, delta) in events) {
            cur += delta
            if (cur > max) max = cur
        }
        return max
    }

    /** Samples of one gesture must share this, otherwise they can never match each other. */
    fun signature(f: Features): String = when (f.kind) {
        Kind.TAP -> "tap:${f.tapGroups}"
        Kind.MOTION -> "motion:${f.fingerCount}:${f.strokeCount}"
    }

    fun describe(f: Features): String = when (f.kind) {
        Kind.TAP -> f.tapGroups.joinToString(" · ") { if (it == 1) "tap" else "$it-finger tap" }
        Kind.MOTION -> buildString {
            append(if (f.fingerCount == 1) "1 finger" else "${f.fingerCount} fingers")
            append(" · ")
            append(if (f.strokeCount == 1) "1 stroke" else "${f.strokeCount} strokes")
        }
    }
}
