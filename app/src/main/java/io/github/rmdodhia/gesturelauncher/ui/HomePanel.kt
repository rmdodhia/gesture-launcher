package io.github.rmdodhia.gesturelauncher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.rmdodhia.gesturelauncher.ActionRunner
import io.github.rmdodhia.gesturelauncher.core.Action
import io.github.rmdodhia.gesturelauncher.core.HomeCommand
import io.github.rmdodhia.gesturelauncher.core.HomeControl
import io.github.rmdodhia.gesturelauncher.home.HomeDeviceInfo
import io.github.rmdodhia.gesturelauncher.home.HomeGateway
import io.github.rmdodhia.gesturelauncher.home.HomeStatus
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** "Google Home" tab of the action picker: connect → pick device → pick command → test/use. */
@Composable
internal fun HomePanel(home: HomeGateway, runner: ActionRunner, current: HomeControl?, onPick: (Action) -> Unit) {
    val status by home.status.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<HomeDeviceInfo>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    // Keep the saved device selectable even if it isn't listed (offline, renamed, list failed).
    var selected by remember {
        mutableStateOf(current?.let { HomeDeviceInfo(it.deviceId, it.deviceName, null, it.command == HomeCommand.BRIGHTNESS) })
    }
    var command by remember { mutableStateOf(current?.command ?: HomeCommand.TOGGLE) }
    var percent by remember { mutableIntStateOf(current?.percent ?: 50) }
    var label by remember {
        mutableStateOf(current?.takeIf { it.label != HomeControl.defaultLabel(it.deviceName, it.command, it.percent) }?.label ?: "")
    }

    LaunchedEffect(status, reload) {
        if (status != HomeStatus.Ready) return@LaunchedEffect
        devices = null
        home.devices()
            .onSuccess { list ->
                devices = list
                // Refresh the saved selection with live info (e.g. whether it can dim).
                selected = selected?.let { s -> list.firstOrNull { it.id == s.id } ?: s }
            }
            .onFailure {
                if (it is CancellationException) throw it
                devices = emptyList()
                message = it.message ?: "Couldn't list devices."
            }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val st = status) {
            is HomeStatus.Unavailable -> Text(st.reason, modifier = Modifier.testTag("homeUnavailable"))
            HomeStatus.Checking -> CircularProgressIndicator()
            HomeStatus.NeedsAccess -> {
                Text("Allow Gesture Launcher to control your Google Home devices.")
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            try {
                                message = home.requestAccess()
                            } finally {
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.testTag("homeConnect"),
                ) { Text("Connect Google Home") }
            }
            HomeStatus.Ready -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Device", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { message = null; reload++ }) { Text("Refresh") }
                }
                val list = devices
                when {
                    list == null -> CircularProgressIndicator()
                    list.isEmpty() && message == null -> Text("No controllable devices found in this home.")
                    else -> list.forEach { d -> DeviceRow(d, selected?.id == d.id) { selected = d } }
                }
            }
        }
        message?.let { Text(it, modifier = Modifier.testTag("homeMessage")) }

        val device = selected
        if (device != null) {
            if (devices?.none { it.id == device.id } != false) {
                Text("Selected: ${device.name}", style = MaterialTheme.typography.bodyMedium)
            }
            Text("Command", style = MaterialTheme.typography.titleMedium)
            val commands = HomeCommand.entries.filter { it != HomeCommand.BRIGHTNESS || device.canDim }
            val cmd = if (command in commands) command else HomeCommand.TOGGLE
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                commands.forEach { c ->
                    FilterChip(
                        selected = cmd == c,
                        onClick = { command = c },
                        label = { Text(c.verb.replaceFirstChar { it.uppercase() }) },
                        modifier = Modifier.testTag("cmd_${c.name}"),
                    )
                }
            }
            if (cmd == HomeCommand.BRIGHTNESS) {
                Text("Brightness: $percent%")
                Slider(
                    value = percent.toFloat(),
                    onValueChange = { percent = it.roundToInt().coerceIn(1, 100) },
                    valueRange = 1f..100f,
                )
            }
            OutlinedTextField(
                label, { label = it }, label = { Text("Label (optional)") }, singleLine = true,
                placeholder = { Text(HomeControl.defaultLabel(device.name, cmd, percent)) },
                modifier = Modifier.fillMaxWidth(),
            )
            val action = HomeControl(device.id, device.name, cmd, percent)
                .let { if (label.isBlank()) it else it.copy(label = label.trim()) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            try {
                                message = runner.run(action) ?: "Done."
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(if (busy) "Running…" else "Test") }
                Button(onClick = { onPick(action) }, modifier = Modifier.testTag("useAction")) { Text("Use this action") }
            }
        }
    }
}

@Composable
private fun DeviceRow(d: HomeDeviceInfo, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp).testTag("device_${d.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = isSelected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.bodyLarge)
            val sub = listOfNotNull(d.room, if (d.canDim) "dimmable" else null).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    HorizontalDivider()
}
