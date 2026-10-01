package io.github.rmdodhia.gesturelauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SendMessageTest {
    @Test
    fun validatesNumbersAndLabels() {
        assertEquals(SendMessage("+1 (555) 010-0199", "Sam"), SendMessage.from(" Sam ", " +1 (555) 010-0199 "))
        assertEquals("Message Sam", SendMessage.from("Sam", "5550100")!!.label)
        assertEquals("Message 5550100", SendMessage.from("", "5550100")!!.label)
        assertEquals("*611#", SendMessage.from("", "*611#")!!.number)
        assertNull(SendMessage.from("Sam", ""))
        assertNull(SendMessage.from("Sam", "12"))
        assertNull(SendMessage.from("Sam", "sam@example.com"))
    }
}
