package io.github.rmdodhia.gesturelauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rmdodhia.gesturelauncher.ActionRunner
import io.github.rmdodhia.gesturelauncher.home.HomeGateway
import io.github.rmdodhia.gesturelauncher.AppInfo
import io.github.rmdodhia.gesturelauncher.core.AppData
import io.github.rmdodhia.gesturelauncher.core.FeatureExtractor
import io.github.rmdodhia.gesturelauncher.core.Gesture
import io.github.rmdodhia.gesturelauncher.core.GestureSample
import io.github.rmdodhia.gesturelauncher.core.Recognizer
import io.github.rmdodhia.gesturelauncher.data.ErrorLog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditGestureScreen(
    initial: Gesture,
    isNew: Boolean,
    data: AppData,
    runner: ActionRunner,
    home: HomeGateway,
    loadApps: suspend () -> List<AppInfo>,
    onSave: (Gesture) -> Unit,
    onDelete: (Gesture) -> Unit,
    onClose: () -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var samples by remember(initial.id) { mutableStateOf(initial.samples) }
    var action by remember(initial.id) { mutableStateOf(initial.action) }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val current = initial.copy(name = name.trim(), samples = samples, action = action)
    val dirty = current != initial.copy(name = initial.name.trim()) || (isNew && (name.isNotBlank() || samples.isNotEmpty() || action != null))

    if (picking) {
        ActionPicker(
            current = action,
            runner = runner,
            home = home,
            loadApps = loadApps,
            onPick = { action = it; picking = false; testResult = null },
            onCancel = { picking = false },
        )
        return
    }

    BackHandler { if (dirty) confirmDiscard = true else onClose() }

    val similar = remember(samples, data.gestures, data.settings.threshold) {
        try {
            Recognizer.similarGestures(samples, data.gestures.filter { it.id != initial.id }, data.settings.threshold)
        } catch (e: Exception) {
            ErrorLog.record("similarGestures", e)
            emptyList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New gesture" else "Edit gesture") },
                navigationIcon = {
                    IconButton(onClick = { if (dirty) confirmDiscard = true else onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (!isNew) TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                    TextButton(
                        onClick = {
                            error = when {
                                name.isBlank() -> "Enter a name."
                                samples.isEmpty() -> "Draw the gesture at least once in the box below."
                                mismatch(samples) != null -> mismatch(samples)
                                else -> null
                            }
                            if (error == null) onSave(current)
                        },
                        modifier = Modifier.testTag("save"),
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                // Keep the recording box clear of the back-gesture edges (at least 16dp either way).
                .windowInsetsPadding(
                    WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal)
                        .union(WindowInsets(left = 16.dp, right = 16.dp)),
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                name, { name = it; error = null }, label = { Text("Name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("nameField"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Action: " + (action?.label ?: "none"),
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(enabled = action != null, onClick = {
                    val a = action ?: return@TextButton
                    testResult = null
                    scope.launch { testResult = runner.run(a) }
                }) {
                    Text("Test")
                }
                TextButton(onClick = { picking = true }, modifier = Modifier.testTag("chooseAction")) { Text("Choose") }
            }
            testResult?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                "Samples: ${samples.size} — draw it 3–5 times below, the way you'll normally do it.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("sampleCount"),
            )
            if (samples.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(samples) { i, s ->
                        Card {
                            Box {
                                SampleThumbnail(s, Modifier.size(72.dp))
                                IconButton(
                                    onClick = { samples = samples.toMutableList().also { it.removeAt(i) } },
                                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
                                ) { Icon(Icons.Filled.Close, "Remove sample ${i + 1}", Modifier.size(16.dp)) }
                            }
                            Text(
                                FeatureExtractor.describe(FeatureExtractor.analyze(s)),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.width(72.dp).padding(4.dp),
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
            if (similar.isNotEmpty()) {
                Text(
                    "Warning: too similar to " + similar.joinToString { "“${it.gesture.name}” (${pct(it.score)})" } +
                        ". It may trigger the wrong action — try a more distinct gesture.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("similarWarning"),
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error")) }
            CaptureSurface(
                endTimeoutMs = data.settings.endTimeoutMs,
                onGesture = { s ->
                    if (!s.isEmpty) {
                        val problem = mismatch(samples + s)
                        if (problem == null) samples = samples + s
                        error = problem
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 16.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .testTag("recordCanvas"),
                hint = "Draw here",
            )
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${initial.name}”?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(initial) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Every sample of a gesture must be the same kind (e.g. all 3-finger taps, or all 1-finger 2-stroke
 * shapes). A stray sample of another kind would let unrelated touches trigger this gesture.
 */
internal fun mismatch(samples: List<GestureSample>): String? {
    val features = samples.filter { !it.isEmpty }.map { FeatureExtractor.analyze(it) }
    val first = features.firstOrNull() ?: return null
    val bad = features.firstOrNull { FeatureExtractor.signature(it) != FeatureExtractor.signature(first) } ?: return null
    return "That was “${FeatureExtractor.describe(bad)}” but the first sample is “${FeatureExtractor.describe(first)}”. " +
        "Draw it the same way each time, or remove the other samples first."
}
