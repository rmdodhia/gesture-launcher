package io.github.rmdodhia.gesturelauncher.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import io.github.rmdodhia.gesturelauncher.ActionRunner
import io.github.rmdodhia.gesturelauncher.AppInfo
import io.github.rmdodhia.gesturelauncher.core.Action
import io.github.rmdodhia.gesturelauncher.core.LaunchApp
import io.github.rmdodhia.gesturelauncher.core.Links
import io.github.rmdodhia.gesturelauncher.core.OpenUri
import io.github.rmdodhia.gesturelauncher.data.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** End-to-end UI flow on the JVM: record → assign action → save → draw → action runs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppFlowTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val ran = mutableListOf<Action>()
    private val runner = ActionRunner { ran += it; null }
    private lateinit var store: Store
    private val apps = listOf(AppInfo("com.example.reader", "Reader"), AppInfo("com.example.music", "Music"))

    @Before
    fun setUp() {
        store = Store(File(tmp.root, "g.json"))
    }

    private fun launch(incoming: Incoming? = null) {
        compose.setContent {
            GestureLauncherApp(store, runner, loadApps = { apps }, incoming = incoming, onIncomingHandled = {})
        }
    }

    /** Draws a polyline in fractions of the node size, then waits past the end-of-gesture timeout. */
    private fun drawShape(tag: String, vararg pts: Pair<Float, Float>) {
        compose.onNodeWithTag(tag).performTouchInput {
            // Same physical size on every canvas, like a real finger (canvases differ in aspect ratio).
            val size = minOf(width, height) * 0.6f
            fun at(p: Pair<Float, Float>) = Offset(centerX + (p.first - 0.5f) * size, centerY + (p.second - 0.5f) * size)
            down(at(pts[0]))
            for (i in 1 until pts.size) {
                val a = at(pts[i - 1])
                val b = at(pts[i])
                for (k in 1..8) moveTo(a + (b - a) * (k / 8f))
            }
            up()
        }
        endGesture()
    }

    private fun threeFingerTap(tag: String) {
        compose.onNodeWithTag(tag).performTouchInput {
            tapWith(this, 3)
        }
        endGesture()
    }

    private fun tapWith(scope: TouchInjectionScope, fingers: Int) = with(scope) {
        for (f in 0 until fingers) down(f, Offset(width * (0.3f + 0.15f * f), height * 0.5f))
        advanceEventTime(60)
        for (f in 0 until fingers) up(f)
    }

    private fun endGesture() {
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
    }

    private val zigzag = arrayOf(0.2f to 0.5f, 0.35f to 0.3f, 0.5f to 0.7f, 0.65f to 0.3f, 0.8f to 0.5f)
    private val circle = Array(25) { i ->
        val a = 2 * Math.PI * i / 24
        (0.5f + 0.25f * Math.cos(a).toFloat()) to (0.5f + 0.25f * Math.sin(a).toFloat())
    }

    private fun createGesture(name: String, appLabel: String, record: () -> Unit) {
        compose.onNodeWithTag("openGestures").performClick()
        compose.onNodeWithTag("newGesture").performClick()
        compose.onNodeWithTag("nameField").performTextInput(name)
        repeat(3) { record() }
        compose.onNodeWithText("Samples: 3", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("chooseAction").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(appLabel).performClick()
        compose.onNodeWithText("Action: $appLabel").assertIsDisplayed()
        compose.onNodeWithTag("save").performClick()
        // Saving hops to an IO thread, which Compose's idling doesn't track.
        compose.waitUntil(5000) { store.data.value.gestures.any { it.name == name } }
        compose.waitForIdle()
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
    }

    @Test
    fun recordShapeThenDrawItToLaunchApp() {
        launch()
        compose.onNodeWithText("No gestures yet", substring = true).assertIsDisplayed()
        createGesture("Zig", "Reader") { drawShape("recordCanvas", *zigzag) }

        assertEquals(1, store.data.value.gestures.size)
        assertEquals(3, store.data.value.gestures[0].samples.size)

        drawShape("drawCanvas", *zigzag)
        assertEquals(listOf<Action>(LaunchApp("com.example.reader", "Reader")), ran)
        compose.onNodeWithText("Zig → Reader").assertIsDisplayed()

        // A clearly different shape must not run anything.
        drawShape("drawCanvas", *circle)
        assertEquals(1, ran.size)
        compose.onNodeWithText("Not recognized").assertIsDisplayed()
    }

    @Test
    fun multiFingerTapAndShapeCoexist() {
        launch()
        createGesture("Three", "Music") { threeFingerTap("recordCanvas") }
        createGesture("Loop", "Reader") { drawShape("recordCanvas", *circle) }

        threeFingerTap("drawCanvas")
        drawShape("drawCanvas", *circle)
        assertEquals(listOf("com.example.music", "com.example.reader"), ran.map { (it as LaunchApp).packageName })
    }

    @Test
    fun saveRequiresNameAndSample() {
        launch()
        compose.onNodeWithTag("openGestures").performClick()
        compose.onNodeWithTag("newGesture").performClick()
        compose.onNodeWithTag("save").performClick()
        compose.onNodeWithText("Enter a name.").assertIsDisplayed()
        compose.onNodeWithTag("nameField").performTextInput("x")
        compose.onNodeWithTag("save").performClick()
        compose.onNodeWithText("Draw the gesture at least once", substring = true).assertIsDisplayed()
        assertTrue(store.data.value.gestures.isEmpty())
    }

    @Test
    fun sharedLinkOpensNewGestureWithAction() {
        val action = OpenUri(Links.kindleUri("B00B7NPRY8"), Links.KINDLE_PACKAGE, "Dune")
        launch(Incoming.SharedLink(action))
        compose.waitForIdle()
        compose.onNodeWithText("New gesture").assertIsDisplayed()
        compose.onNodeWithText("Action: Dune").assertIsDisplayed()
        drawShape("recordCanvas", *zigzag)
        compose.onNodeWithTag("save").performClick()
        compose.waitUntil(5000) { store.data.value.gestures.isNotEmpty() }
        assertEquals(action, store.data.value.gestures.single().action)
        assertEquals("Dune", store.data.value.gestures.single().name)
    }

    @Test
    fun kindlePickerBuildsDeepLink() {
        launch()
        compose.onNodeWithTag("openGestures").performClick()
        compose.onNodeWithTag("newGesture").performClick()
        compose.onNodeWithTag("chooseAction").performClick()
        compose.onNodeWithTag("type_KINDLE").performClick()
        compose.onNodeWithTag("actionLabel").performTextInput("Dune")
        compose.onNodeWithTag("actionInput").performTextInput("https://www.amazon.com/dp/B00B7NPRY8/")
        compose.onNodeWithText("Will open: kindle://book?action=open&asin=B00B7NPRY8").assertIsDisplayed()
        compose.onNodeWithTag("useAction").performClick()
        compose.onNodeWithText("Action: Dune").assertIsDisplayed()
    }
}
