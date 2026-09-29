package io.github.rmdodhia.gesturelauncher.data

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records crashes and caught errors to a local log so problems on the phone are visible
 * without a debugger. Fatal crashes set a flag so the app shows the report on next launch.
 */
object ErrorLog {
    private const val TAG = "GestureLauncher"
    private const val MAX_BYTES = 64 * 1024
    private lateinit var logFile: File
    private lateinit var crashFlag: File

    fun install(context: Context) {
        logFile = File(context.filesDir, "error_log.txt")
        crashFlag = File(context.filesDir, "crashed.flag")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                append("CRASH", e)
                crashFlag.writeText("1")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Logs a caught (non-fatal) error. Safe to call before [install] (then it only logs to logcat). */
    fun record(where: String, e: Throwable) {
        Log.e(TAG, where, e)
        if (::logFile.isInitialized) runCatching { append(where, e) }
    }

    fun read(): String = if (::logFile.isInitialized && logFile.exists()) logFile.readText() else ""

    fun consumeCrashFlag(): Boolean =
        ::crashFlag.isInitialized && crashFlag.exists() && crashFlag.delete()

    fun clear() {
        if (::logFile.isInitialized) logFile.delete()
    }

    @Synchronized
    private fun append(where: String, e: Throwable) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = buildString {
            append("=== $time [$where] ")
            append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append(e.stackTraceToString())
            append("\n")
        }
        val existing = if (logFile.exists()) logFile.readText() else ""
        // Keep the newest entries when the log grows too large.
        logFile.writeText((existing + entry).takeLast(MAX_BYTES))
    }
}
