package io.github.rmdodhia.gesturelauncher.ui

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.rmdodhia.gesturelauncher.ActionRunner
import io.github.rmdodhia.gesturelauncher.home.HOME_SDK_MISSING
import io.github.rmdodhia.gesturelauncher.home.HomeGateway
import io.github.rmdodhia.gesturelauncher.home.UnavailableHome
import io.github.rmdodhia.gesturelauncher.AppInfo
import io.github.rmdodhia.gesturelauncher.books.BookSource
import io.github.rmdodhia.gesturelauncher.books.Library
import io.github.rmdodhia.gesturelauncher.core.Gesture
import io.github.rmdodhia.gesturelauncher.core.Links
import io.github.rmdodhia.gesturelauncher.core.OpenUri
import io.github.rmdodhia.gesturelauncher.data.ErrorLog
import io.github.rmdodhia.gesturelauncher.data.Store
import kotlinx.coroutines.launch
import java.util.UUID

private sealed interface Screen {
    data object Draw : Screen
    data object List : Screen
    data class Edit(val gesture: Gesture, val isNew: Boolean) : Screen
}

/** Something the activity received from outside (share sheet, Quick Settings tile). */
sealed interface Incoming {
    data class SharedLink(val action: OpenUri) : Incoming
    data class SharedWithoutLink(val text: String) : Incoming
    data object OpenCanvas : Incoming
}

@Composable
fun GestureLauncherApp(
    store: Store,
    runner: ActionRunner,
    home: HomeGateway = UnavailableHome(HOME_SDK_MISSING),
    loadApps: suspend () -> List<AppInfo>,
    bookSources: List<BookSource> = emptyList(),
    incoming: Incoming?,
    onIncomingHandled: () -> Unit,
    startupMessage: String? = null,
    crashReport: String? = null,
) {
    val data by store.data.collectAsState()
    var screen by remember { mutableStateOf<Screen>(Screen.Draw) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var crash by remember { mutableStateOf(crashReport) }
    val context = LocalContext.current
    val library = remember(store, bookSources) { Library(store, bookSources) }

    fun showMessage(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    /** Runs a store write; on failure keeps the user on the current screen and explains. */
    fun persist(what: String, after: () -> Unit = {}, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                after()
            } catch (e: Exception) {
                ErrorLog.record(what, e)
                showMessage("Couldn't save: ${e.message}")
            }
        }
    }

    LaunchedEffect(startupMessage) { startupMessage?.let { showMessage(it) } }
    LaunchedEffect(incoming) {
        when (incoming) {
            null -> return@LaunchedEffect
            is Incoming.SharedLink -> {
                val action = incoming.action
                val newGesture = Screen.Edit(Gesture(UUID.randomUUID().toString(), action.label, emptyList(), action), isNew = true)
                // Books you share in are usually the ones you're reading.
                val book = Links.bookFrom(action)?.copy(reading = true)
                if (book == null) {
                    screen = newGesture
                } else {
                    // Books go on the shelf (Choose action → Book); offer a gesture straight away too.
                    persist("add shared book") {
                        store.addBook(book)
                        scope.launch {
                            val r = snackbar.showSnackbar(
                                "Added \"${book.title}\" to Books",
                                actionLabel = "Make gesture",
                                duration = SnackbarDuration.Long,
                            )
                            if (r == SnackbarResult.ActionPerformed && screen !is Screen.Edit) screen = newGesture
                        }
                    }
                }
            }
            is Incoming.SharedWithoutLink -> showMessage("No link found in the shared text.")
            // Don't throw away an open editor; only leave the list screen.
            Incoming.OpenCanvas -> if (screen == Screen.List) screen = Screen.Draw
        }
        onIncomingHandled()
    }

    AppTheme {
        Box(Modifier.fillMaxSize()) {
            when (val s = screen) {
                Screen.Draw -> DrawScreen(data, runner, onOpenGestures = { screen = Screen.List })
                Screen.List -> GestureListScreen(
                    data = data,
                    exportJson = store::export,
                    onImport = { d -> persist("import", { showMessage("Imported ${d.gestures.size} gestures.") }) { store.replaceAll(d) } },
                    onSettings = { st -> persist("settings") { store.updateSettings(st) } },
                    onEdit = { screen = Screen.Edit(it, isNew = false) },
                    onNew = { screen = Screen.Edit(Gesture(UUID.randomUUID().toString(), "", emptyList()), isNew = true) },
                    onBack = { screen = Screen.Draw },
                    showMessage = ::showMessage,
                )
                is Screen.Edit -> EditGestureScreen(
                    initial = s.gesture,
                    isNew = s.isNew,
                    data = data,
                    runner = runner,
                    home = home,
                    loadApps = loadApps,
                    library = library,
                    onSave = { g -> persist("save", { screen = Screen.List }) { store.upsertGesture(g) } },
                    onDelete = { g -> persist("delete", { screen = Screen.List }) { store.deleteGesture(g.id) } },
                    onClose = { screen = Screen.List },
                )
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }

        crash?.let { report ->
            AlertDialog(
                onDismissRequest = { crash = null },
                title = { Text("The app crashed last time") },
                text = {
                    Text(
                        report.takeLast(4000),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = { TextButton(onClick = { crash = null }) { Text("Close") } },
                dismissButton = {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report),
                                "Share crash report",
                            ),
                        )
                    }) { Text("Share") }
                },
            )
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
