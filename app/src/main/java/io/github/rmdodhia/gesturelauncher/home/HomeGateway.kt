package io.github.rmdodhia.gesturelauncher.home

import androidx.activity.ComponentActivity
import io.github.rmdodhia.gesturelauncher.core.HomeControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface HomeStatus {
    /** Home control can't work in this build/device; [reason] is shown to the user. */
    data class Unavailable(val reason: String) : HomeStatus
    data object Checking : HomeStatus
    data object NeedsAccess : HomeStatus
    data object Ready : HomeStatus
}

data class HomeDeviceInfo(val id: String, val name: String, val room: String?, val canDim: Boolean)

/**
 * The app's only view of Google Home. The real implementation (src/home) wraps the Home APIs SDK and is
 * compiled only when the SDK is installed; otherwise src/nohome provides an [UnavailableHome].
 * Implementations never throw from [requestAccess], [devices] or [run]; errors come back as messages.
 */
interface HomeGateway {
    val status: StateFlow<HomeStatus>

    /** Must be called from Activity.onCreate (the SDK registers an activity-result launcher). */
    fun attach(activity: ComponentActivity)

    /** Shows Google's consent screen. Returns null on success or a message. */
    suspend fun requestAccess(): String?

    /** Devices that can be switched on/off, or a message explaining why none could be listed. */
    suspend fun devices(): Result<List<HomeDeviceInfo>>

    /** Returns null on success or a user-facing error message. */
    suspend fun run(action: HomeControl): String?
}

class UnavailableHome(reason: String) : HomeGateway {
    override val status: StateFlow<HomeStatus> = MutableStateFlow(HomeStatus.Unavailable(reason)).asStateFlow()
    private val message = reason

    override fun attach(activity: ComponentActivity) = Unit
    override suspend fun requestAccess(): String = message
    override suspend fun devices(): Result<List<HomeDeviceInfo>> = Result.failure(IllegalStateException(message))
    override suspend fun run(action: HomeControl): String = message
}

const val HOME_SDK_MISSING =
    "Google Home control isn't included in this build: the Google Home APIs SDK isn't installed. " +
        "See README → Google Home setup."
