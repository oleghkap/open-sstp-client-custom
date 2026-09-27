package kittoku.osc.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


class ClientStartGateTest {
    @Test
    fun supersededAttemptDoesNotStartAfterTheNextConnect() {
        val gate = ClientStartGate<String>()
        val started = mutableListOf<String>()
        val first = gate.invalidate { }
        val second = gate.invalidate { }

        assertFalse(gate.tryStart(first) { started += "old"; "old" })
        assertTrue(gate.tryStart(second) { started += "new"; "new" })

        assertEquals(listOf("new"), started)
        assertEquals("new", gate.release())
    }

    @Test
    fun clientStartedBeforeSupersedeIsAbandoned() {
        val gate = ClientStartGate<String>()
        val abandoned = mutableListOf<String>()
        val first = gate.invalidate { }

        assertTrue(gate.tryStart(first) { "old" })
        gate.invalidate { abandoned += it }

        assertEquals(listOf("old"), abandoned)
        assertFalse(gate.tryStart(first) { "again" })
        assertNull(gate.release())
    }
}
