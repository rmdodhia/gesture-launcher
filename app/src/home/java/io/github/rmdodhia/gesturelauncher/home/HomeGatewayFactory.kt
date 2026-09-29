package io.github.rmdodhia.gesturelauncher.home

import android.content.Context
import androidx.activity.ComponentActivity
import com.google.home.DeviceType
import com.google.home.FactoryRegistry
import com.google.home.Home
import com.google.home.HomeClient
import com.google.home.HomeConfig
import com.google.home.HomeDevice
import com.google.home.PermissionsResultStatus
import com.google.home.PermissionsState
import com.google.home.matter.standard.ColorTemperatureLightDevice
import com.google.home.matter.standard.DimmableLightDevice
import com.google.home.matter.standard.DimmablePlugInUnitDevice
import com.google.home.matter.standard.ExtendedColorLightDevice
import com.google.home.matter.standard.LevelControl
import com.google.home.matter.standard.LevelControlTrait
import com.google.home.matter.standard.OnOff
import com.google.home.matter.standard.OnOffLightDevice
import com.google.home.matter.standard.OnOffLightSwitchDevice
import com.google.home.matter.standard.OnOffPluginUnitDevice
import io.github.rmdodhia.gesturelauncher.core.HomeCommand
import io.github.rmdodhia.gesturelauncher.core.HomeControl
import io.github.rmdodhia.gesturelauncher.data.ErrorLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Build with the Google Home APIs SDK (Gradle property homeSdk is set). */
fun createHomeGateway(context: Context): HomeGateway = GoogleHomeGateway(context.applicationContext)

private const val TIMEOUT_MS = 15_000L

private class GoogleHomeGateway(context: Context) : HomeGateway {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _status = MutableStateFlow<HomeStatus>(HomeStatus.Checking)
    override val status: StateFlow<HomeStatus> = _status.asStateFlow()

    private val client: HomeClient = Home.getClient(
        context,
        homeConfig = HomeConfig(
            coroutineContext = Dispatchers.IO,
            // Types missing here are invisible to types(), so every on/off-capable type we want must be listed.
            factoryRegistry = FactoryRegistry(
                types = listOf(
                    OnOffLightDevice,
                    DimmableLightDevice,
                    ColorTemperatureLightDevice,
                    ExtendedColorLightDevice,
                    OnOffPluginUnitDevice,
                    DimmablePlugInUnitDevice,
                    OnOffLightSwitchDevice,
                ),
                traits = listOf(OnOff, LevelControl),
            ),
        ),
    )

    init {
        scope.launch {
            try {
                client.hasPermissions().collect { state ->
                    _status.value = when (state) {
                        PermissionsState.GRANTED -> HomeStatus.Ready
                        PermissionsState.NOT_GRANTED, PermissionsState.PERMISSIONS_STATE_UNAVAILABLE -> HomeStatus.NeedsAccess
                        else -> HomeStatus.Checking
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.record("home permissions", e)
                _status.value = HomeStatus.Unavailable("Google Home isn't available: ${describe(e)}")
            }
        }
    }

    override fun attach(activity: ComponentActivity) {
        client.registerActivityResultCallerForPermissions(activity)
    }

    override suspend fun requestAccess(): String? = guard("home requestAccess", timeoutMs = null, needsAccess = false) {
        val result = client.requestPermissions()
        when (result.status) {
            PermissionsResultStatus.SUCCESS -> null
            PermissionsResultStatus.CANCELLED -> "Cancelled."
            else -> "Couldn't get access: ${result.errorMessage ?: result.status}. " +
                "Check the OAuth client / test-user setup in README → Google Home setup."
        }
    }

    override suspend fun devices(): Result<List<HomeDeviceInfo>> {
        var list: List<HomeDeviceInfo>? = null
        val error = guard("home devices") {
            list = client.devices().list()
                .mapNotNull { d ->
                    val types = d.types().first()
                    if (types.none { it.trait(OnOff) != null }) return@mapNotNull null
                    val room = try {
                        d.room()?.name
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    HomeDeviceInfo(d.id.id, d.name, room, canDim = types.any { it.trait(LevelControl) != null })
                }
                .sortedWith(compareBy({ it.room ?: "~" }, { it.name.lowercase() }))
            null
        }
        return list?.let { Result.success(it) } ?: Result.failure(IllegalStateException(error ?: "Couldn't list devices."))
    }

    override suspend fun run(action: HomeControl): String? = guard("home run") {
        val device = client.devices().list().firstOrNull { it.id.id == action.deviceId }
            ?: return@guard "\"${action.deviceName}\" wasn't found in Google Home. Was it removed, or is access limited to another home?"
        val types = device.types().first()
        when (action.command) {
            HomeCommand.ON -> onOff(device, types).on()
            HomeCommand.OFF -> onOff(device, types).off()
            HomeCommand.TOGGLE -> {
                val t = onOff(device, types)
                if (t.supports(OnOff.Command.Toggle)) t.toggle() else if (t.onOff == true) t.off() else t.on()
            }
            HomeCommand.BRIGHTNESS -> {
                val level = types.firstNotNullOfOrNull { it.trait(LevelControl) }
                    ?: throw UnsupportedOperationException("${device.name} can't be dimmed")
                level.moveToLevelWithOnOff(
                    level = HomeControl.levelFor(action.percent).toUByte(),
                    transitionTime = null,
                    optionsMask = LevelControlTrait.OptionsBitmap(),
                    optionsOverride = LevelControlTrait.OptionsBitmap(),
                )
            }
        }
        null
    }

    private fun onOff(device: HomeDevice, types: Set<DeviceType>): OnOff =
        types.firstNotNullOfOrNull { it.trait(OnOff) }
            ?: throw UnsupportedOperationException("${device.name} can't be switched on/off")

    /** Runs [block] with a timeout, turning every failure into a message. */
    private suspend fun guard(
        what: String,
        timeoutMs: Long? = TIMEOUT_MS,
        needsAccess: Boolean = true,
        block: suspend () -> String?,
    ): String? {
        when (val st = status.value) {
            is HomeStatus.Unavailable -> return st.reason
            HomeStatus.NeedsAccess -> if (needsAccess) {
                return "Google Home isn't connected yet: edit a gesture → Choose → Google Home → Connect."
            }
            else -> Unit
        }
        return try {
            if (timeoutMs == null) {
                block()
            } else {
                val done = withTimeoutOrNull(timeoutMs) { Wrapped(block()) }
                    ?: return "Google Home didn't respond in ${timeoutMs / 1000}s."
                done.value
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.record(what, e)
            describe(e)
        }
    }

    private class Wrapped(val value: String?)

    private fun describe(e: Exception): String = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "error"
}
