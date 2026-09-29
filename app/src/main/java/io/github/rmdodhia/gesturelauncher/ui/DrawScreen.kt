package io.github.rmdodhia.gesturelauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.rmdodhia.gesturelauncher.ActionRunner
import io.github.rmdodhia.gesturelauncher.core.AppData
import io.github.rmdodhia.gesturelauncher.core.GestureSample
import io.github.rmdodhia.gesturelauncher.core.Recognition
import io.github.rmdodhia.gesturelauncher.core.Recognizer
import io.github.rmdodhia.gesturelauncher.data.ErrorLog
import kotlinx.coroutines.delay

private data class Status(val text: String, val seq: Long)

@Composable
fun DrawScreen(data: AppData, runner: ActionRunner, onOpenGestures: () -> Unit) {
    var status by remember { mutableStateOf<Status?>(null) }
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(status) {
        if (status != null) {
            delay(4000)
            status = null
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        CaptureSurface(
            endTimeoutMs = data.settings.endTimeoutMs,
            onGesture = { sample ->
                val (text, ok) = handleGesture(sample, data, runner)
                if (text != null) {
                    haptics.performHapticFeedback(if (ok) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
                    status = Status(text, System.nanoTime())
                }
            },
            // Inset from the back-gesture edges so strokes can't be stolen by the system.
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal))
                .testTag("drawCanvas"),
            hint = if (data.gestures.isEmpty()) "No gestures yet.\nTap “Gestures” to record one." else "Draw a gesture",
        )
        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                status?.text ?: "",
                modifier = Modifier.weight(1f).padding(top = 10.dp, end = 8.dp).testTag("status"),
                style = MaterialTheme.typography.bodyLarge,
            )
            FilledTonalButton(onClick = onOpenGestures, modifier = Modifier.testTag("openGestures")) {
                Text("Gestures")
            }
        }
    }
}

/** Returns the status text to show (null = nothing) and whether it was a success. */
internal fun handleGesture(sample: GestureSample, data: AppData, runner: ActionRunner): Pair<String?, Boolean> {
    val result = try {
        Recognizer.recognize(sample, data.gestures, data.settings.threshold)
    } catch (e: Exception) {
        ErrorLog.record("recognize", e)
        return "Recognition error: ${e.message}" to false
    }
    val debug = data.settings.showDebug
    return when (result) {
        Recognition.Empty -> null to false
        is Recognition.Recognized -> {
            val g = result.match.gesture
            val score = if (debug) " (${pct(result.match.score)})" else ""
            val action = g.action ?: return "${g.name}$score — no action set" to false
            val error = runner.run(action)
            if (error == null) "${g.name} → ${action.label}$score" to true else error to false
        }
        is Recognition.NotRecognized -> {
            val text = when {
                !debug -> "Not recognized"
                result.ambiguousWith != null ->
                    "Ambiguous: ${result.best?.gesture?.name} (${pct(result.best?.score)}) vs " +
                        "${result.ambiguousWith.gesture.name} (${pct(result.ambiguousWith.score)})"
                result.best != null -> "Not recognized — closest: ${result.best.gesture.name} (${pct(result.best.score)})"
                else -> "Not recognized — no gesture of this type"
            }
            text to false
        }
    }
}

internal fun pct(score: Float?): String = if (score == null) "?" else "${(score * 100).toInt()}%"
