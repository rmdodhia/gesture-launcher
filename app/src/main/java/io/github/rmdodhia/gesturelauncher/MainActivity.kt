package io.github.rmdodhia.gesturelauncher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import io.github.rmdodhia.gesturelauncher.core.Links
import io.github.rmdodhia.gesturelauncher.data.ErrorLog
import io.github.rmdodhia.gesturelauncher.ui.GestureLauncherApp
import io.github.rmdodhia.gesturelauncher.ui.Incoming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf<Incoming?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as GestureApp
        val startupMessage = app.startupMessage.also { app.startupMessage = null }
        val crashReport = if (ErrorLog.consumeCrashFlag()) ErrorLog.read() else null
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            GestureLauncherApp(
                store = app.store,
                runner = app.runner,
                loadApps = { withContext(Dispatchers.IO) { installedApps(applicationContext) } },
                incoming = incoming.value,
                onIncomingHandled = { incoming.value = null },
                startupMessage = startupMessage,
                crashReport = crashReport,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when {
            intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
                val subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()
                val referrerPkg = referrer?.takeIf { it.scheme == "android-app" }?.host
                val action = runCatching { Links.actionFromShare(text, subject, referrerPkg) }
                    .onFailure { ErrorLog.record("share", it) }
                    .getOrNull()
                incoming.value = if (action != null) Incoming.SharedLink(action) else Incoming.SharedWithoutLink(text)
            }
            intent.getBooleanExtra(EXTRA_OPEN_CANVAS, false) -> incoming.value = Incoming.OpenCanvas
        }
    }

    companion object {
        const val EXTRA_OPEN_CANVAS = "open_canvas"
    }
}
