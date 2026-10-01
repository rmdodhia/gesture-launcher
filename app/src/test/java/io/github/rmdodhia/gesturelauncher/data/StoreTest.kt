package io.github.rmdodhia.gesturelauncher.data

import io.github.rmdodhia.gesturelauncher.core.AppData
import io.github.rmdodhia.gesturelauncher.core.Book
import io.github.rmdodhia.gesturelauncher.core.BookApp
import io.github.rmdodhia.gesturelauncher.core.Gesture
import io.github.rmdodhia.gesturelauncher.core.GestureSample
import io.github.rmdodhia.gesturelauncher.core.HomeCommand
import io.github.rmdodhia.gesturelauncher.core.HomeControl
import io.github.rmdodhia.gesturelauncher.core.LaunchApp
import io.github.rmdodhia.gesturelauncher.core.OpenUri
import io.github.rmdodhia.gesturelauncher.core.SendMessage
import io.github.rmdodhia.gesturelauncher.core.Settings
import io.github.rmdodhia.gesturelauncher.core.TouchPoint
import io.github.rmdodhia.gesturelauncher.core.Track
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val sample = GestureSample(listOf(Track(listOf(TouchPoint(1f, 2f, 0), TouchPoint(3f, 4f, 16)))), 2.5f)
    private val g1 = Gesture("1", "Circle", listOf(sample), LaunchApp("com.x", "X"))
    private val g2 = Gesture("2", "Book", listOf(sample), OpenUri("kindle://book?action=open&asin=B000000000", "com.amazon.kindle", "Dune"))

    @Test
    fun roundTripPersistsAcrossInstances() = runTest {
        val f = File(tmp.root, "g.json")
        val s = Store(f)
        assertNull(s.load())
        s.upsertGesture(g1)
        s.upsertGesture(g2)
        s.upsertGesture(g1.copy(name = "Circle 2"))
        s.upsertGesture(g1.copy(id = "3", name = "Text", action = SendMessage("+15550100199", "Sam")))
        s.updateSettings(Settings(threshold = 0.7f, endTimeoutMs = 900, showDebug = true))

        val reloaded = Store(f)
        assertNull(reloaded.load())
        assertEquals(listOf("Circle 2", "Book", "Text"), reloaded.data.value.gestures.map { it.name })
        assertEquals(g2.action, reloaded.data.value.gestures[1].action)
        assertEquals(SendMessage("+15550100199", "Sam", "Message Sam"), reloaded.data.value.gestures[2].action)
        assertEquals(900L, reloaded.data.value.settings.endTimeoutMs)
        assertTrue(!File(tmp.root, "g.json.tmp").exists())

        reloaded.deleteGesture("1")
        assertEquals(listOf("2", "3"), Store(f).also { it.load() }.data.value.gestures.map { it.id })
    }

    @Test
    fun corruptFileIsQuarantinedNotDeleted() {
        val f = File(tmp.root, "g.json")
        f.writeText("{ this is not json")
        val s = Store(f)
        val msg = s.load()
        assertNotNull(msg)
        assertEquals(AppData(), s.data.value)
        val backups = tmp.root.listFiles()!!.filter { it.name.startsWith("g.json.corrupt-") }
        assertEquals(1, backups.size)
        assertEquals("{ this is not json", backups[0].readText())
    }

    @Test
    fun bookSyncKeepsHandAddedBooksAndOtherApps() = runTest {
        val f = File(tmp.root, "g.json")
        val s = Store(f)
        fun play(id: String, reading: Boolean = false) = Book(BookApp.PLAY_BOOKS, id, "P$id", uri = "u$id", reading = reading)
        val kindle = Book(BookApp.KINDLE, "B000000001", "K", uri = "kindle://x")
        s.addBook(kindle)
        s.addBook(play("manual").copy(title = "Mine"))
        s.syncBooks(BookApp.PLAY_BOOKS, listOf(play("a", reading = true), play("b"), play("b")))
        assertEquals(setOf("KINDLE:B000000001", "PLAY_BOOKS:manual", "PLAY_BOOKS:a", "PLAY_BOOKS:b"), s.data.value.books.map { it.key }.toSet())
        assertTrue(s.data.value.books.single { it.id == "a" }.synced)

        // Next sync: "a" gone, "manual" now returned by the service → synced details, but stays hand-added.
        s.syncBooks(BookApp.PLAY_BOOKS, listOf(play("b"), play("manual")))
        val books = Store(f).also { it.load() }.data.value.books
        assertEquals(setOf("KINDLE:B000000001", "PLAY_BOOKS:b", "PLAY_BOOKS:manual"), books.map { it.key }.toSet())
        assertEquals(false, books.single { it.id == "manual" }.synced)

        s.syncBooks(BookApp.PLAY_BOOKS, emptyList())
        assertEquals(setOf("KINDLE:B000000001", "PLAY_BOOKS:manual"), s.data.value.books.map { it.key }.toSet())

        // Adding an existing book again doesn't duplicate or overwrite it.
        s.addBook(kindle.copy(title = "Other", author = "A"))
        assertEquals(Book(BookApp.KINDLE, "B000000001", "K", "A", "kindle://x"), s.data.value.books.single { it.app == BookApp.KINDLE })
        s.setReading(kindle.key, true)
        assertTrue(s.data.value.books.single { it.app == BookApp.KINDLE }.reading)
        s.removeBook(kindle.key)
        assertTrue(s.data.value.books.none { it.app == BookApp.KINDLE })
    }

    @Test
    fun olderFilesWithoutBooksStillLoad() {
        val d = Store.decode("""{"version":1,"gestures":[],"settings":{}}""")
        assertTrue(d.books.isEmpty())
    }

    @Test
    fun toleratesUnknownFieldsAndMissingDefaults() {
        val d = Store.decode("""{"gestures":[{"id":"a","name":"n","samples":[],"future":1}],"extra":true}""")
        assertEquals("n", d.gestures[0].name)
        assertNull(d.gestures[0].action)
        assertEquals(Settings(), d.settings)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateIds() {
        Store.decode(Store.encode(AppData(gestures = listOf(g1, g1))))
    }

    @Test
    fun actionJsonUsesStableTypeNames() {
        val json = Store.encode(AppData(gestures = listOf(g1, g2)))
        assertTrue(json, json.contains("\"type\":\"app\"") && json.contains("\"type\":\"uri\""))
    }

    @Test
    fun homeActionRoundTripsWithDefaults() {
        val h = HomeControl("dev-1", "Lamp", HomeCommand.BRIGHTNESS, 30)
        assertEquals("Lamp: 30%", h.label)
        val back = Store.decode(Store.encode(AppData(gestures = listOf(g1.copy(action = h)))))
        assertEquals(h, back.gestures.single().action)
        assertTrue(Store.encode(AppData(gestures = listOf(g1.copy(action = h)))).contains("\"type\":\"home\""))
        // Hand-written/older JSON without percent or label still loads.
        val minimal = Store.decode(
            """{"gestures":[{"id":"a","name":"n","samples":[],"action":{"type":"home","deviceId":"d","deviceName":"Fan","command":"TOGGLE"}}]}""",
        ).gestures.single().action as HomeControl
        assertEquals(100, minimal.percent)
        assertEquals("Fan: toggle", minimal.label)
    }

    @Test
    fun brightnessPercentMapsToMatterLevel() {
        assertEquals(3, HomeControl.levelFor(0)) // clamped to 1 %, never "off"
        assertEquals(3, HomeControl.levelFor(1))
        assertEquals(127, HomeControl.levelFor(50))
        assertEquals(254, HomeControl.levelFor(100))
        assertEquals(254, HomeControl.levelFor(250))
    }
}
