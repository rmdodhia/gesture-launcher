package io.github.rmdodhia.gesturelauncher.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import io.github.rmdodhia.gesturelauncher.ActionRunner
import io.github.rmdodhia.gesturelauncher.AppInfo
import io.github.rmdodhia.gesturelauncher.books.BookSource
import io.github.rmdodhia.gesturelauncher.books.FetchResult
import io.github.rmdodhia.gesturelauncher.core.Action
import io.github.rmdodhia.gesturelauncher.core.Book
import io.github.rmdodhia.gesturelauncher.core.BookApp
import io.github.rmdodhia.gesturelauncher.core.LaunchApp
import io.github.rmdodhia.gesturelauncher.core.Links
import io.github.rmdodhia.gesturelauncher.core.OpenUri
import io.github.rmdodhia.gesturelauncher.core.SendMessage
import io.github.rmdodhia.gesturelauncher.data.Store
import io.github.rmdodhia.gesturelauncher.core.HomeCommand
import io.github.rmdodhia.gesturelauncher.core.HomeControl
import io.github.rmdodhia.gesturelauncher.home.HOME_SDK_MISSING
import io.github.rmdodhia.gesturelauncher.home.HomeDeviceInfo
import io.github.rmdodhia.gesturelauncher.home.HomeGateway
import io.github.rmdodhia.gesturelauncher.home.HomeStatus
import io.github.rmdodhia.gesturelauncher.home.UnavailableHome
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
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

    private fun launch(
        incoming: Incoming? = null,
        home: HomeGateway = UnavailableHome(HOME_SDK_MISSING),
        bookSources: List<BookSource> = emptyList(),
    ) {
        compose.setContent {
            GestureLauncherApp(
                store, runner, home = home, loadApps = { apps }, bookSources = bookSources,
                incoming = incoming, onIncomingHandled = {},
            )
        }
    }

    private class FakeHome(initial: HomeStatus, val list: List<HomeDeviceInfo>) : HomeGateway {
        override val status = MutableStateFlow(initial)
        var accessRequests = 0
        override fun attach(activity: ComponentActivity) = Unit
        override suspend fun requestAccess(): String? {
            accessRequests++
            status.value = HomeStatus.Ready
            return null
        }
        override suspend fun devices() = Result.success(list)
        override suspend fun run(action: HomeControl): String? = null
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
    fun sharedBookGoesOnShelfAndCanBecomeGesture() {
        val action = OpenUri(Links.kindleUri("B00B7NPRY8"), Links.KINDLE_PACKAGE, "Dune")
        launch(Incoming.SharedLink(action))
        compose.waitUntil(5000) { store.data.value.books.isNotEmpty() }
        assertEquals(Book(BookApp.KINDLE, "B00B7NPRY8", "Dune", uri = action.uri, reading = true), store.data.value.books.single())
        // The snackbar appears asynchronously (slower on CI).
        compose.waitUntil(5000) { compose.onAllNodesWithText("Make gesture").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Make gesture").performClick()
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
    fun sharedNonBookLinkOpensNewGesture() {
        val action = OpenUri("https://example.com/x", null, "Example")
        launch(Incoming.SharedLink(action))
        compose.waitForIdle()
        compose.onNodeWithText("New gesture").assertIsDisplayed()
        compose.onNodeWithText("Action: Example").assertIsDisplayed()
        assertTrue(store.data.value.books.isEmpty())
    }

    /** Needs consent until an interactive fetch, then returns [books]. */
    private class FakeBooks(val books: List<Book>) : BookSource {
        override val app = BookApp.PLAY_BOOKS
        var connected = false
        override fun attach(activity: ComponentActivity) = Unit
        override suspend fun fetch(interactive: Boolean): FetchResult {
            if (interactive) connected = true
            return if (connected) FetchResult.Books(books) else FetchResult.NeedsConsent
        }
    }

    private fun openBookPicker() {
        compose.onNodeWithTag("openGestures").performClick()
        compose.onNodeWithTag("newGesture").performClick()
        compose.onNodeWithTag("nameField").performTextInput("Read")
        repeat(3) { drawShape("recordCanvas", *zigzag) }
        compose.onNodeWithTag("chooseAction").performClick()
        compose.onNodeWithTag("type_BOOK").performClick()
        compose.waitForIdle()
    }

    @Test
    fun bookPickerMergesAppsAndOpensTheRightOne() = runTest {
        val emma = Book(BookApp.PLAY_BOOKS, "vol1", "Emma", "Jane Austen", Links.playBooksUri("vol1"), reading = true)
        val circe = Book(BookApp.LIBBY, "https://share.libbyapp.com/title/1", "Circe", uri = "https://share.libbyapp.com/title/1")
        store.addBook(circe)
        launch(bookSources = listOf(FakeBooks(listOf(emma, emma.copy(id = "vol2", title = "Persuasion", reading = false)))))
        openBookPicker()

        compose.onNodeWithTag("connect_PLAY_BOOKS").performClick()
        compose.waitUntil(5000) { store.data.value.books.size == 3 }
        compose.onNodeWithText("Reading now").assertIsDisplayed()
        compose.onNodeWithText("Jane Austen · Play Books").assertIsDisplayed()
        compose.onNodeWithText("Libby").assertIsDisplayed()

        compose.onNodeWithTag("bookSearch").performTextInput("emm")
        compose.onNodeWithText("Circe").assertDoesNotExist()
        compose.onNodeWithTag("book_PLAY_BOOKS:vol1").performClick()
        compose.onNodeWithTag("useAction").performClick()
        compose.onNodeWithText("Action: Emma").assertIsDisplayed()
        compose.onNodeWithTag("save").performClick()
        compose.waitUntil(5000) { store.data.value.gestures.isNotEmpty() }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()

        drawShape("drawCanvas", *zigzag)
        assertEquals(listOf<Action>(OpenUri(Links.playBooksUri("vol1"), Links.PLAY_BOOKS_PACKAGE, "Emma")), ran)
    }

    @Test
    fun addBookByLink() {
        launch()
        openBookPicker()
        compose.onNodeWithText("No books yet", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("howToAdd").performClick()
        compose.onNodeWithText("Tap \"Recommend this book\"", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Kindle isn't installed.").assertExists()
        compose.onNodeWithTag("addBook").performScrollTo().performClick()
        compose.onNodeWithTag("addBookTitle").performTextInput("Dune")
        compose.onNodeWithTag("addBookLink").performTextInput("https://example.com/nope")
        compose.onNodeWithText("Not a Kindle/Amazon, Libby or Play Books link").assertIsDisplayed()
        compose.onNodeWithTag("addBookLink").performTextReplacement("https://www.amazon.com/dp/B00B7NPRY8/")
        compose.onNodeWithTag("addBookConfirm").performClick()
        compose.waitUntil(5000) { store.data.value.books.isNotEmpty() }
        assertEquals(
            Book(BookApp.KINDLE, "B00B7NPRY8", "Dune", uri = Links.kindleUri("B00B7NPRY8"), reading = true),
            store.data.value.books.single(),
        )
        // The new book is pre-selected.
        compose.onNodeWithTag("useAction").performClick()
        compose.onNodeWithText("Action: Dune").assertIsDisplayed()
    }

    @Test
    fun homeDeviceActionConnectsPicksAndRunsFromGesture() {
        val lamp = HomeDeviceInfo("lamp-1", "Desk lamp", "Office", canDim = true)
        val plug = HomeDeviceInfo("plug-1", "Fan plug", null, canDim = false)
        val home = FakeHome(HomeStatus.NeedsAccess, listOf(lamp, plug))
        launch(home = home)
        compose.onNodeWithTag("openGestures").performClick()
        compose.onNodeWithTag("newGesture").performClick()
        compose.onNodeWithTag("nameField").performTextInput("Lamp")
        repeat(3) { drawShape("recordCanvas", *zigzag) }
        compose.onNodeWithTag("chooseAction").performClick()
        compose.onNodeWithTag("type_HOME").performClick()
        compose.onNodeWithTag("homeConnect").performClick()
        compose.waitForIdle()
        assertEquals(1, home.accessRequests)

        // Plug can't dim, so Brightness isn't offered for it.
        compose.onNodeWithTag("device_plug-1").performClick()
        compose.onNodeWithTag("cmd_BRIGHTNESS").assertDoesNotExist()
        compose.onNodeWithTag("device_lamp-1").performClick()
        compose.onNodeWithText("Office · dimmable").assertIsDisplayed()
        compose.onNodeWithTag("cmd_BRIGHTNESS").performScrollTo().performClick()
        compose.onNodeWithTag("useAction").performScrollTo().performClick()
        compose.onNodeWithText("Action: Desk lamp: 50%").assertIsDisplayed()
        compose.onNodeWithTag("save").performClick()
        compose.waitUntil(5000) { store.data.value.gestures.isNotEmpty() }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()

        drawShape("drawCanvas", *zigzag)
        compose.waitForIdle()
        assertEquals(listOf<Action>(HomeControl("lamp-1", "Desk lamp", HomeCommand.BRIGHTNESS, 50)), ran)
    }

    @Test
    fun messageActionByTypedNumberRunsFromGesture() {
        launch()
        compose.onNodeWithTag("openGestures").performClick()
        compose.onNodeWithTag("newGesture").performClick()
        compose.onNodeWithTag("nameField").performTextInput("Text Sam")
        repeat(3) { drawShape("recordCanvas", *zigzag) }
        compose.onNodeWithTag("chooseAction").performClick()
        compose.onNodeWithTag("type_MESSAGE").performClick()
        compose.onNodeWithTag("pickContact").assertIsDisplayed()
        compose.onNodeWithTag("messageName").performTextInput("Sam")
        compose.onNodeWithTag("messageNumber").performTextInput("hello")
        compose.onNodeWithText("Not a phone number").assertIsDisplayed()
        compose.onNodeWithTag("messageNumber").performTextReplacement("+1 (555) 010-0199")
        compose.onNodeWithTag("useAction").performClick()
        compose.onNodeWithText("Action: Message Sam").assertIsDisplayed()
        compose.onNodeWithTag("save").performClick()
        compose.waitUntil(5000) { store.data.value.gestures.isNotEmpty() }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()

        drawShape("drawCanvas", *zigzag)
        compose.waitForIdle()
        assertEquals(listOf<Action>(SendMessage("+1 (555) 010-0199", "Sam")), ran)
    }

    @Test
    fun homeTabExplainsWhenSdkMissing() {
        launch()
        compose.onNodeWithTag("openGestures").performClick()
        compose.onNodeWithTag("newGesture").performClick()
        compose.onNodeWithTag("chooseAction").performClick()
        compose.onNodeWithTag("type_HOME").performClick()
        compose.onNodeWithTag("homeUnavailable").assertIsDisplayed()
        compose.onNodeWithTag("useAction").assertDoesNotExist()
    }
}
