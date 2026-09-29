package io.github.rmdodhia.gesturelauncher

import android.app.Application
import io.github.rmdodhia.gesturelauncher.data.ErrorLog
import io.github.rmdodhia.gesturelauncher.data.Store
import java.io.File

class GestureApp : Application() {
    lateinit var store: Store
        private set
    lateinit var runner: ActionRunner
        private set

    /** Set if saved data couldn't be loaded; shown once by the UI. */
    var startupMessage: String? = null

    override fun onCreate() {
        super.onCreate()
        ErrorLog.install(this)
        store = Store(File(filesDir, "gestures.json"))
        startupMessage = store.load()
        runner = AndroidActionRunner(this)
    }
}
