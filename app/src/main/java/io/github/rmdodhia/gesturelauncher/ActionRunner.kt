package io.github.rmdodhia.gesturelauncher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import androidx.core.net.toUri
import io.github.rmdodhia.gesturelauncher.core.Action
import io.github.rmdodhia.gesturelauncher.core.HomeControl
import io.github.rmdodhia.gesturelauncher.core.LaunchApp
import io.github.rmdodhia.gesturelauncher.core.Links
import io.github.rmdodhia.gesturelauncher.core.OpenUri
import io.github.rmdodhia.gesturelauncher.core.SendMessage
import io.github.rmdodhia.gesturelauncher.data.ErrorLog
import io.github.rmdodhia.gesturelauncher.home.HomeGateway
import kotlinx.coroutines.CancellationException

/** Runs an [Action]. Returns null on success or a user-facing error message. */
fun interface ActionRunner {
    suspend fun run(action: Action): String?
}

class AndroidActionRunner(private val context: Context, private val home: () -> HomeGateway) : ActionRunner {
    override suspend fun run(action: Action): String? = try {
        when (action) {
            is LaunchApp -> launchApp(action.packageName, action.label)
            is OpenUri -> openUri(action)
            is HomeControl -> home().run(action)
            is SendMessage -> sendMessage(action)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ErrorLog.record("run ${action::class.simpleName}", e)
        "Couldn't run \"${action.label}\": ${e.message ?: e::class.simpleName}"
    }

    private fun launchApp(pkg: String, label: String): String? {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "\"$label\" isn't installed (or can't be launched)."
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }

    /**
     * Tries the link in the chosen app (or its equivalents). If that app is installed but can't handle the
     * link, opens the app itself (the user picked it); otherwise lets any app (e.g. the browser) open the link.
     */
    private fun openUri(a: OpenUri): String? {
        if (a.uri.isBlank()) return "No link set for \"${a.label}\"."
        var uri = a.uri.trim()
        // Gestures saved before v0.2 used the Play Store page; open the reader instead.
        if (a.packageName == Links.PLAY_BOOKS_PACKAGE && uri.contains("/store/books/details")) {
            Links.extractPlayBooksId(uri)?.let { uri = Links.playBooksUri(it) }
        }
        val base = Intent(Intent.ACTION_VIEW, uri.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val packages = a.packageName?.let { Links.equivalentPackages(it) }.orEmpty()
        for (intent in packages.map { Intent(base).setPackage(it) }) {
            if (tryStart(intent)) return null
        }
        for (pkg in packages) {
            context.packageManager.getLaunchIntentForPackage(pkg)?.let {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "The app couldn't open this link directly, so it was opened instead."
            }
        }
        return if (tryStart(base)) null else "No app can open this link."
    }

    /** Opens a new message to the number, preferring the default SMS app over a chooser. */
    private fun sendMessage(a: SendMessage): String? {
        if (a.number.isBlank()) return "No number set for \"${a.label}\"."
        val base = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", a.number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Telephony.Sms.getDefaultSmsPackage(context)?.let { if (tryStart(Intent(base).setPackage(it))) return null }
        return if (tryStart(base)) null else "No messaging app is installed."
    }

    private fun tryStart(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

data class AppInfo(val packageName: String, val label: String)

fun installedApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    }
    return resolved
        .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}
