package io.github.rmdodhia.gesturelauncher.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.rmdodhia.gesturelauncher.core.AppData
import io.github.rmdodhia.gesturelauncher.core.Gesture
import io.github.rmdodhia.gesturelauncher.core.Settings
import io.github.rmdodhia.gesturelauncher.data.ErrorLog
import io.github.rmdodhia.gesturelauncher.data.Store
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureListScreen(
    data: AppData,
    exportJson: () -> String,
    onImport: (AppData) -> Unit,
    onSettings: (Settings) -> Unit,
    onEdit: (Gesture) -> Unit,
    onNew: () -> Unit,
    onBack: () -> Unit,
    showMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<AppData?>(null) }

    BackHandler(onBack = onBack)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(exportJson().toByteArray()) }
            showMessage("Exported ${data.gestures.size} gestures.")
        } catch (e: Exception) {
            ErrorLog.record("export", e)
            showMessage("Export failed: ${e.message}")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            pendingImport = Store.decode(text)
        } catch (e: Exception) {
            ErrorLog.record("import", e)
            showMessage("Import failed: not a valid gesture backup (${e.message})")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gestures") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menuOpen = false; showSettings = true })
                            DropdownMenuItem(text = { Text("Export backup") }, onClick = {
                                menuOpen = false
                                exportLauncher.launch("gestures-backup.json")
                            })
                            DropdownMenuItem(text = { Text("Import backup") }, onClick = {
                                menuOpen = false
                                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                            })
                            DropdownMenuItem(text = { Text("Error log") }, onClick = { menuOpen = false; showLog = true })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New gesture") },
                modifier = Modifier.testTag("newGesture"),
            )
        },
    ) { padding ->
        if (data.gestures.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("No gestures yet. Tap “New gesture”, draw it a few times, and choose what it opens.")
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(data.gestures, key = { it.id }) { g ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onEdit(g) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        g.samples.firstOrNull()?.let { SampleThumbnail(it, Modifier.size(48.dp)) }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(g.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                (g.action?.label ?: "No action") + " · ${g.samples.size} samples",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showSettings) SettingsDialog(data.settings, onDismiss = { showSettings = false }, onSave = { showSettings = false; onSettings(it) })
    if (showLog) ErrorLogDialog(onDismiss = { showLog = false }, share = { text ->
        context.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share error log"),
        )
    })
    pendingImport?.let { imported ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Replace gestures?") },
            text = { Text("Replace your ${data.gestures.size} gestures and settings with the ${imported.gestures.size} in the backup?") },
            confirmButton = { TextButton(onClick = { pendingImport = null; onImport(imported) }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsDialog(initial: Settings, onDismiss: () -> Unit, onSave: (Settings) -> Unit) {
    var s by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Strictness: ${pct(s.threshold)}")
                Text(
                    "Higher = fewer wrong actions but more “not recognized”.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = s.threshold,
                    onValueChange = { s = s.copy(threshold = it) },
                    valueRange = Settings.MIN_THRESHOLD..Settings.MAX_THRESHOLD,
                )
                Text("End-of-gesture pause: ${s.endTimeoutMs} ms")
                Text(
                    "How long to wait after lifting your fingers before a gesture counts as finished. " +
                        "Increase for slow multi-stroke shapes or tap rhythms.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = s.endTimeoutMs.toFloat(),
                    onValueChange = { s = s.copy(endTimeoutMs = (it / 50f).roundToLong() * 50) },
                    valueRange = Settings.MIN_TIMEOUT_MS.toFloat()..Settings.MAX_TIMEOUT_MS.toFloat(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show match scores", Modifier.weight(1f))
                    Switch(checked = s.showDebug, onCheckedChange = { s = s.copy(showDebug = it) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(s) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { s = Settings() }) { Text("Defaults") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun ErrorLogDialog(onDismiss: () -> Unit, share: (String) -> Unit) {
    var log by remember { mutableStateOf(ErrorLog.read()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Error log") },
        text = {
            Text(
                log.ifBlank { "No errors recorded." },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            Row {
                TextButton(enabled = log.isNotBlank(), onClick = { share(log) }) { Text("Share") }
                TextButton(enabled = log.isNotBlank(), onClick = { ErrorLog.clear(); log = "" }) { Text("Clear") }
            }
        },
    )
}
