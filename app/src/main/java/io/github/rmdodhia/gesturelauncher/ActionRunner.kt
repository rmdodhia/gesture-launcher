package io.github.rmdodhia.gesturelauncher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri
import io.github.rmdodhia.gesturelauncher.core.Action
import io.github.rmdodhia.gesturelauncher.core.LaunchApp
import io.github.rmdodhia.gesturelauncher.core.OpenUri
import io.github.rmdodhia.gesturelauncher.data.ErrorLog

/** Runs an [Action]. Returns null on success or a user-facing error message. */
fun interface ActionRunner {
    fun run(action: Action): String?
}

class AndroidActionRunner(private val context: Context) : ActionRunner {
    override fun run(action: Action): String? = try {
        when (action) {
            is LaunchApp -> launchApp(action.packageName, action.label)
            is OpenUri -> openUri(action)
        }
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

    /** Tries the link in the chosen app, then any app, then just opens the chosen app. */
    private fun openUri(a: OpenUri): String? {
        if (a.uri.isBlank()) return "No link set for \"${a.label}\"."
        val base = Intent(Intent.ACTION_VIEW, a.uri.trim().toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val attempts = buildList {
            if (a.packageName != null) add(Intent(base).setPackage(a.packageName))
            add(base)
        }
        for (intent in attempts) {
            try {
                context.startActivity(intent)
                return null
            } catch (_: ActivityNotFoundException) {
                // try next
            }
        }
        if (a.packageName != null) {
            context.packageManager.getLaunchIntentForPackage(a.packageName)?.let {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "Link not supported; opened the app instead."
            }
        }
        return "No app can open this link."
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
