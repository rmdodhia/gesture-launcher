package io.github.rmdodhia.gesturelauncher.data

import io.github.rmdodhia.gesturelauncher.core.AppData
import io.github.rmdodhia.gesturelauncher.core.Gesture
import io.github.rmdodhia.gesturelauncher.core.GestureSample
import io.github.rmdodhia.gesturelauncher.core.LaunchApp
import io.github.rmdodhia.gesturelauncher.core.OpenUri
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
        s.updateSettings(Settings(threshold = 0.7f, endTimeoutMs = 900, showDebug = true))

        val reloaded = Store(f)
        assertNull(reloaded.load())
        assertEquals(listOf("Circle 2", "Book"), reloaded.data.value.gestures.map { it.name })
        assertEquals(g2.action, reloaded.data.value.gestures[1].action)
        assertEquals(900L, reloaded.data.value.settings.endTimeoutMs)
        assertTrue(!File(tmp.root, "g.json.tmp").exists())

        reloaded.deleteGesture("1")
        assertEquals(listOf("2"), Store(f).also { it.load() }.data.value.gestures.map { it.id })
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
}
