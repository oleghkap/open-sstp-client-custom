package kittoku.osc.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


class ReconnectPermitTest {
    @Test
    fun decisionCapturedBeforeDisconnectDoesNotRun() {
        val permit = ReconnectPermit()
        val epoch = permit.capture()

        permit.suppress { }

        assertNull(permit.runIfCurrent(epoch) { "reconnect" })
        assertFalse(permit.isCurrent(epoch))
    }

    @Test
    fun decisionCapturedAfterDisconnectDoesNotRun() {
        val permit = ReconnectPermit()
        permit.suppress { }
        val epoch = permit.capture()

        assertNull(permit.runIfCurrent(epoch) { "reconnect" })
        assertFalse(permit.isCurrent(epoch))
    }

    @Test
    fun aNewConnectAllowsOnlyADecisionCapturedAfterIt() {
        val permit = ReconnectPermit()
        val beforeDisconnect = permit.capture()
        permit.suppress { }
        val duringDisconnect = permit.capture()
        permit.arm { }
        val afterConnect = permit.capture()

        assertNull(permit.runIfCurrent(beforeDisconnect) { "old" })
        assertNull(permit.runIfCurrent(duringDisconnect) { "disconnected" })
        assertEquals("new", permit.runIfCurrent(afterConnect) { "new" })
        assertTrue(permit.isCurrent(afterConnect))
        assertFalse(permit.isCurrent(beforeDisconnect))
    }

    @Test
    fun suppressWaitsUntilTheCurrentDecisionFinishes() {
        val permit = ReconnectPermit()
        val epoch = permit.capture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            permit.runIfCurrent(epoch) {
                entered.countDown()
                release.await()
                "held"
            }
        }
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        val finished = AtomicBoolean(false)
        val suppressor = Thread {
            permit.suppress { finished.set(true) }
        }
        suppressor.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (suppressor.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertEquals(Thread.State.BLOCKED, suppressor.state)
        assertFalse(finished.get())

        release.countDown()
        suppressor.join(2000)
        worker.join(2000)

        assertFalse(suppressor.isAlive)
        assertFalse(worker.isAlive)
        assertTrue(finished.get())
        assertNull(permit.runIfCurrent(epoch) { "again" })
    }
}
