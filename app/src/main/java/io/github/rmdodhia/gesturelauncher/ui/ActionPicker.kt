package io.github.rmdodhia.gesturelauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.rmdodhia.gesturelauncher.ActionRunner
import io.github.rmdodhia.gesturelauncher.AppInfo
import io.github.rmdodhia.gesturelauncher.core.Action
import io.github.rmdodhia.gesturelauncher.core.LaunchApp
import io.github.rmdodhia.gesturelauncher.core.Links
import io.github.rmdodhia.gesturelauncher.core.OpenUri
import io.github.rmdodhia.gesturelauncher.core.HomeControl
import io.github.rmdodhia.gesturelauncher.data.ErrorLog
import io.github.rmdodhia.gesturelauncher.home.HomeGateway
import kotlinx.coroutines.launch

private enum class ActionType(val title: String) {
    APP("App"), HOME("Google Home"), KINDLE("Kindle book"), PLAY("Play Books"), LINK("Libby / link"),
}

private val linkTargets = listOf(
    null to "Any app",
    Links.LIBBY_PACKAGE to "Libby",
    Links.KINDLE_PACKAGE to "Kindle",
    Links.PLAY_BOOKS_PACKAGE to "Play Books",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionPicker(
    current: Action?,
    runner: ActionRunner,
    home: HomeGateway,
    loadApps: suspend () -> List<AppInfo>,
    onPick: (Action) -> Unit,
    onCancel: () -> Unit,
) {
    val initialType = when {
        current is LaunchApp -> ActionType.APP
        current is HomeControl -> ActionType.HOME
        current is OpenUri && current.uri.startsWith("kindle://") -> ActionType.KINDLE
        current is OpenUri && current.packageName == Links.PLAY_BOOKS_PACKAGE -> ActionType.PLAY
        current is OpenUri -> ActionType.LINK
        else -> ActionType.APP
    }
    var type by remember { mutableStateOf(initialType) }
    val openUri = current as? OpenUri
    var title by remember { mutableStateOf(openUri?.label ?: "") }
    var input by remember {
        mutableStateOf(
            when (initialType) {
                ActionType.KINDLE -> openUri?.uri?.let { Links.extractAsin(it) } ?: ""
                ActionType.PLAY -> openUri?.uri?.let { Links.extractPlayBooksId(it) } ?: ""
                ActionType.LINK -> openUri?.uri ?: ""
                ActionType.APP, ActionType.HOME -> ""
            },
        )
    }
    var linkPackage by remember { mutableStateOf(openUri?.packageName) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onCancel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose action") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionType.entries.forEach { t ->
                    FilterChip(
                        selected = type == t,
                        onClick = {
                            if (type != t) {
                                type = t
                                input = ""
                                testResult = null
                            }
                        },
                        label = { Text(t.title) },
                        modifier = Modifier.testTag("type_${t.name}"),
                    )
                }
            }
            if (type == ActionType.APP) {
                AppList(loadApps) { onPick(LaunchApp(it.packageName, it.label)) }
                return@Column
            }
            if (type == ActionType.HOME) {
                HomePanel(home, runner, current as? HomeControl, onPick)
                return@Column
            }

            val action: OpenUri? = when (type) {
                ActionType.KINDLE -> Links.extractAsin(input)?.let {
                    OpenUri(Links.kindleUri(it), Links.KINDLE_PACKAGE, title.ifBlank { "Kindle book $it" })
                }
                ActionType.PLAY -> Links.extractPlayBooksId(input)?.let {
                    OpenUri(Links.playBooksUri(it), Links.PLAY_BOOKS_PACKAGE, title.ifBlank { "Play Books $it" })
                }
                else -> input.trim().takeIf { it.contains("://") }?.let {
                    OpenUri(it, linkPackage, title.ifBlank { Links.hostOf(it) ?: it })
                }
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    title, { title = it }, label = { Text("Label (e.g. book title)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("actionLabel"),
                )
                val (fieldLabel, help) = when (type) {
                    ActionType.KINDLE -> "ASIN or Amazon link" to
                        "The 10-character ID from the book's Amazon page URL (starts with B0…). The book must be in your Kindle library."
                    ActionType.PLAY -> "Volume ID or Play Books link" to
                        "Share the book from Play Books, or paste its Google Play URL (the part after id=)."
                    else -> "Link (https://… or app://…)" to
                        "Tip: in Libby (or any app) use Share → Gesture Launcher to create a gesture from a link automatically."
                }
                OutlinedTextField(
                    input, { input = it; testResult = null }, label = { Text(fieldLabel) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("actionInput"),
                    isError = input.isNotBlank() && action == null,
                    supportingText = {
                        Text(if (input.isNotBlank() && action == null) "Not recognized — check the value" else help)
                    },
                )
                if (type == ActionType.LINK) {
                    Text("Open with", style = MaterialTheme.typography.labelLarge)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        linkTargets.forEach { (pkg, name) ->
                            FilterChip(selected = linkPackage == pkg, onClick = { linkPackage = pkg }, label = { Text(name) })
                        }
                    }
                }
                if (action != null) {
                    Text("Will open: ${action.uri}", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        enabled = action != null,
                        onClick = {
                            val a = action ?: return@OutlinedButton
                            testResult = null
                            scope.launch { testResult = runner.run(a) ?: "Opened — check it went to the right place." }
                        },
                    ) { Text("Test") }
                    Button(
                        enabled = action != null,
                        onClick = { action?.let(onPick) },
                        modifier = Modifier.testTag("useAction"),
                    ) { Text("Use this action") }
                }
                testResult?.let { Text(it, modifier = Modifier.testTag("testResult")) }
            }
        }
    }
}

@Composable
private fun AppList(loadApps: suspend () -> List<AppInfo>, onClick: (AppInfo) -> Unit) {
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            apps = loadApps()
        } catch (e: Exception) {
            ErrorLog.record("loadApps", e)
            error = "Couldn't list apps: ${e.message}"
        }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            query, { query = it }, label = { Text("Search apps") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("appSearch"),
        )
        val list = apps
        when {
            error != null -> Text(error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> {
                val filtered = list.filter {
                    query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.packageName }) { app ->
                        Column(Modifier.fillMaxWidth().clickable { onClick(app) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
