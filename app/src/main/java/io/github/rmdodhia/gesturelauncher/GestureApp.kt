package io.github.rmdodhia.gesturelauncher

import android.app.Application
import io.github.rmdodhia.gesturelauncher.data.ErrorLog
import io.github.rmdodhia.gesturelauncher.data.Store
import io.github.rmdodhia.gesturelauncher.home.HomeGateway
import io.github.rmdodhia.gesturelauncher.home.UnavailableHome
import io.github.rmdodhia.gesturelauncher.home.createHomeGateway
import java.io.File

class GestureApp : Application() {
    lateinit var store: Store
        private set
    lateinit var runner: ActionRunner
        private set

    /** Created on first use so a broken Home SDK can't stop the app from starting. */
    val home: HomeGateway by lazy {
        try {
            createHomeGateway(this)
        } catch (e: Throwable) {
            ErrorLog.record("home init", e)
            UnavailableHome("Google Home failed to start: ${e.message ?: e::class.simpleName}")
        }
    }

    /** Set if saved data couldn't be loaded; shown once by the UI. */
    var startupMessage: String? = null

    override fun onCreate() {
        super.onCreate()
        ErrorLog.install(this)
        store = Store(File(filesDir, "gestures.json"))
        startupMessage = store.load()
        runner = AndroidActionRunner(this) { home }
    }
}
