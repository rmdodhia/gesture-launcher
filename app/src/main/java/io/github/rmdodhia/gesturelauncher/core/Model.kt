package io.github.rmdodhia.gesturelauncher.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A single touch point. Coordinates in pixels, [t] in ms since the gesture started. */
@Serializable
data class TouchPoint(val x: Float, val y: Float, val t: Long)

/** One finger contact, from finger-down to finger-up. */
@Serializable
data class Track(val points: List<TouchPoint>) {
    val start: Long get() = points.first().t
    val end: Long get() = points.last().t
}

/**
 * Everything captured between the first finger-down and the end-of-gesture timeout.
 * [density] is pixels per dp at capture time, used for size-dependent thresholds (tap slop).
 */
@Serializable
data class GestureSample(val tracks: List<Track>, val density: Float = 1f) {
    val isEmpty: Boolean get() = tracks.none { it.points.isNotEmpty() }
}

@Serializable
sealed interface Action {
    val label: String
}

@Serializable
@SerialName("app")
data class LaunchApp(val packageName: String, override val label: String) : Action

/** Opens a URI (deep link or web link), optionally in a specific app. */
@Serializable
@SerialName("uri")
data class OpenUri(
    val uri: String,
    val packageName: String? = null,
    override val label: String,
) : Action

/** Opens the default messaging app with a new message to [number]. */
@Serializable
@SerialName("sms")
data class SendMessage(
    val number: String,
    val name: String,
    override val label: String = defaultLabel(name, number),
) : Action {
    companion object {
        fun defaultLabel(name: String, number: String): String = "Message ${name.ifBlank { number }}"

        /** Null unless [number] looks like a phone number (at least 3 digits; only dialable characters). */
        fun from(name: String, number: String): SendMessage? {
            val n = number.trim()
            if (n.count { it.isDigit() } < 3 || n.any { !it.isDigit() && it !in "+-() .#*" }) return null
            return SendMessage(n, name.trim())
        }
    }
}

enum class HomeCommand(val verb: String) { ON("on"), OFF("off"), TOGGLE("toggle"), BRIGHTNESS("brightness") }

/** Controls a Google Home device (light, plug, …) via the Home APIs. [percent] is used for BRIGHTNESS. */
@Serializable
@SerialName("home")
data class HomeControl(
    val deviceId: String,
    val deviceName: String,
    val command: HomeCommand,
    val percent: Int = 100,
    override val label: String = defaultLabel(deviceName, command, percent),
) : Action {
    companion object {
        fun defaultLabel(deviceName: String, command: HomeCommand, percent: Int): String =
            if (command == HomeCommand.BRIGHTNESS) "$deviceName: ${percent.coerceIn(1, 100)}%" else "$deviceName: ${command.verb}"

        /** Matter LevelControl for lights uses 1..254; map 1..100 % onto it. */
        fun levelFor(percent: Int): Int = ((percent.coerceIn(1, 100) * 254 + 50) / 100).coerceIn(1, 254)
    }
}

@Serializable
data class Gesture(
    val id: String,
    val name: String,
    val samples: List<GestureSample>,
    val action: Action? = null,
)

@Serializable
data class Settings(
    /** Minimum match score (0..1) to accept a gesture. Higher = stricter. */
    val threshold: Float = DEFAULT_THRESHOLD,
    /** Time with no fingers down after which the gesture is considered finished. */
    val endTimeoutMs: Long = 600,
    val showDebug: Boolean = false,
) {
    companion object {
        const val DEFAULT_THRESHOLD = 0.80f
        const val MIN_THRESHOLD = 0.50f
        const val MAX_THRESHOLD = 0.95f
        const val MIN_TIMEOUT_MS = 250L
        const val MAX_TIMEOUT_MS = 1500L
    }
}

@Serializable
enum class BookApp(val label: String) { KINDLE("Kindle"), LIBBY("Libby"), PLAY_BOOKS("Play Books") }

/** A book on the unified shelf. [id] is the ASIN, Play Books volume ID, or Libby title ID (else the link). */
@Serializable
data class Book(
    val app: BookApp,
    val id: String,
    val title: String,
    val author: String? = null,
    val uri: String,
    val reading: Boolean = false,
    /** True if it came from an automatic sync (replaced on the next sync); false if the user added it. */
    val synced: Boolean = false,
) {
    val key: String get() = "${app.name}:$id"
}

@Serializable
data class AppData(
    val version: Int = 1,
    val gestures: List<Gesture> = emptyList(),
    val settings: Settings = Settings(),
    val books: List<Book> = emptyList(),
)
