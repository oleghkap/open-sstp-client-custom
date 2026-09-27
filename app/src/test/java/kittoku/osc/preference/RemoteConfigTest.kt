package kittoku.osc.preference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit


class RemoteConfigTest {
    @Test
    fun missingKeysAreResetToDefaults() {
        val profile = decodeRemoteProfile(
            """
            {
              "stringSetting": {"HOME_HOSTNAME": "vpn.example.com"},
              "intSetting": {"SSL_PORT": 8443}
            }
            """.trimIndent().toByteArray()
        )

        val writes = remoteSettingWrites(profile)

        assertEquals(RemoteSettingWrite.Str(OscPrefKey.HOME_HOSTNAME, "vpn.example.com"), writeFor(writes, OscPrefKey.HOME_HOSTNAME))
        assertEquals(RemoteSettingWrite.IntVal(OscPrefKey.SSL_PORT, 8443), writeFor(writes, OscPrefKey.SSL_PORT))
        assertEquals(RemoteSettingWrite.Str(OscPrefKey.HOME_PASSWORD, ""), writeFor(writes, OscPrefKey.HOME_PASSWORD))
        assertEquals(RemoteSettingWrite.Bool(OscPrefKey.SSL_DO_VERIFY, true), writeFor(writes, OscPrefKey.SSL_DO_VERIFY))
        assertEquals(null, writeFor(writes, OscPrefKey.ROOT_STATE))
        assertEquals(null, writeFor(writes, OscPrefKey.RECONNECTION_LIFE))
        assertEquals(null, writeFor(writes, OscPrefKey.SSL_CERT_DIR))
    }

    @Test
    fun blockedAndUnknownKeysAreDropped() {
        val profile = Profile(
            booleanSetting = mutableMapOf(
                OscPrefKey.ROOT_STATE.name to true,
                OscPrefKey.REMOTE_CONFIG_ENABLED.name to true,
            ),
            intSetting = mutableMapOf(
                OscPrefKey.RECONNECTION_LIFE.name to 2,
                "NOT_A_KEY" to 1,
            ),
            stringSetting = mutableMapOf(
                OscPrefKey.HOME_USERNAME.name to "alice",
                OscPrefKey.REMOTE_CONFIG_URL.name to " https://vpn.example.com/config.json ",
                OscPrefKey.HOME_STATUS.name to "connected",
                OscPrefKey.REMOTE_CONFIG_STATUS.name to "forged",
            ),
            uriSetting = mutableMapOf(OscPrefKey.SSL_CERT_DIR.name to "content://certs"),
        )

        val writes = remoteSettingWrites(profile)

        assertEquals(RemoteSettingWrite.Bool(OscPrefKey.REMOTE_CONFIG_ENABLED, true), writeFor(writes, OscPrefKey.REMOTE_CONFIG_ENABLED))
        assertEquals(RemoteSettingWrite.Str(OscPrefKey.HOME_USERNAME, "alice"), writeFor(writes, OscPrefKey.HOME_USERNAME))
        assertEquals(
            RemoteSettingWrite.Str(OscPrefKey.REMOTE_CONFIG_URL, "https://vpn.example.com/config.json"),
            writeFor(writes, OscPrefKey.REMOTE_CONFIG_URL),
        )
        assertEquals(RemoteSettingWrite.Str(OscPrefKey.HOME_PASSWORD, ""), writeFor(writes, OscPrefKey.HOME_PASSWORD))
        assertEquals(null, writeFor(writes, OscPrefKey.ROOT_STATE))
        assertEquals(null, writeFor(writes, OscPrefKey.RECONNECTION_LIFE))
        assertEquals(null, writeFor(writes, OscPrefKey.HOME_STATUS))
        assertEquals(null, writeFor(writes, OscPrefKey.REMOTE_CONFIG_STATUS))
        assertEquals(null, writeFor(writes, OscPrefKey.SSL_CERT_DIR))
    }

    @Test
    fun remoteConfigUrlMustStayHttps() {
        val profile = Profile(
            stringSetting = mutableMapOf(
                OscPrefKey.REMOTE_CONFIG_URL.name to "http://vpn.example.com/config.json",
                OscPrefKey.HOME_HOSTNAME.name to "vpn.example.com",
            ),
        )

        val writes = remoteSettingWrites(profile)

        assertEquals(RemoteSettingWrite.Str(OscPrefKey.HOME_HOSTNAME, "vpn.example.com"), writeFor(writes, OscPrefKey.HOME_HOSTNAME))
        assertEquals(null, writeFor(writes, OscPrefKey.REMOTE_CONFIG_URL))
    }

    @Test
    fun blankRemoteConfigUrlClearsTheAddress() {
        val profile = Profile(
            stringSetting = mutableMapOf(OscPrefKey.REMOTE_CONFIG_URL.name to "  "),
        )

        assertEquals(
            RemoteSettingWrite.Str(OscPrefKey.REMOTE_CONFIG_URL, ""),
            writeFor(remoteSettingWrites(profile), OscPrefKey.REMOTE_CONFIG_URL),
        )
    }

    @Test
    fun emptyStringIsAnExplicitValue() {
        val profile = Profile(
            stringSetting = mutableMapOf(OscPrefKey.HOME_PASSWORD.name to ""),
        )

        assertEquals(
            RemoteSettingWrite.Str(OscPrefKey.HOME_PASSWORD, ""),
            writeFor(remoteSettingWrites(profile), OscPrefKey.HOME_PASSWORD),
        )
    }

    @Test
    fun exportedProfileRoundTripsHostname() {
        val encoded = Json.encodeToString(
            Profile(stringSetting = mutableMapOf(OscPrefKey.HOME_HOSTNAME.name to "vpn.example.com"))
        )

        assertTrue(encoded.contains("HOME_HOSTNAME"))
        val writes = remoteSettingWrites(decodeRemoteProfile(encoded.toByteArray()))
        assertEquals(
            RemoteSettingWrite.Str(OscPrefKey.HOME_HOSTNAME, "vpn.example.com"),
            writeFor(writes, OscPrefKey.HOME_HOSTNAME),
        )
    }

    @Test
    fun extraJsonFieldIsIgnored() {
        val profile = decodeRemoteProfile(
            """{"comment":"hi","stringSetting":{"HOME_HOSTNAME":"vpn.example.com"}}""".toByteArray()
        )

        assertEquals(
            RemoteSettingWrite.Str(OscPrefKey.HOME_HOSTNAME, "vpn.example.com"),
            writeFor(remoteSettingWrites(profile), OscPrefKey.HOME_HOSTNAME),
        )
    }

    @Test
    fun utf8BomIsAccepted() {
        val body = "\uFEFF{\"stringSetting\":{\"HOME_HOSTNAME\":\"vpn.example.com\"}}".toByteArray()
        assertEquals(
            RemoteSettingWrite.Str(OscPrefKey.HOME_HOSTNAME, "vpn.example.com"),
            writeFor(remoteSettingWrites(decodeRemoteProfile(body)), OscPrefKey.HOME_HOSTNAME),
        )
    }

    @Test
    fun invalidJsonIsRejected() {
        try {
            decodeRemoteProfile("not-json".toByteArray())
            fail()
        } catch (_: SerializationException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun onlyHttpsUrlsAreAccepted() {
        assertEquals("vpn.example.com", requireHttps(" https://vpn.example.com/config.json ").host)

        expectFailure(RemoteConfigFailure.NOT_HTTPS) {
            requireHttps("http://vpn.example.com/config.json")
        }
        expectFailure(RemoteConfigFailure.INVALID_URL) {
            requireHttps("vpn.example.com/config.json")
        }
        expectFailure(RemoteConfigFailure.INVALID_URL) {
            requireHttps("https:///config.json")
        }
    }

    @Test
    fun redirectsStayOnHttps() {
        val current = URI("https://vpn.example.com/a/config.json")

        assertEquals(
            "https://vpn.example.com/a/next.json",
            resolveHttps(current, "next.json").toString(),
        )
        assertEquals(
            "https://cdn.example.com/config.json",
            resolveHttps(current, "https://cdn.example.com/config.json").toString(),
        )
        expectFailure(RemoteConfigFailure.NOT_HTTPS) {
            resolveHttps(current, "http://vpn.example.com/config.json")
        }
    }

    @Test(timeout = 5_000)
    fun cancelledBlockingCallReturnsWhileTheWorkIsStuck() = runBlocking {
        val started = CountDownLatch(1)
        val aborted = CountDownLatch(1)
        val release = java.util.concurrent.atomic.AtomicBoolean(false)
        val job = launch(Dispatchers.IO) {
            blockingCall({ aborted.countDown() }) {
                started.countDown()
                while (!release.get()) {
                    try {
                        Thread.sleep(60_000)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }

        assertTrue(started.await(2, TimeUnit.SECONDS))
        val startedAt = System.nanoTime()
        job.cancelAndJoin()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        release.set(true)
        assertTrue(aborted.await(1, TimeUnit.SECONDS))
        assertTrue("cancel took ${elapsedMs}ms", elapsedMs < 1_000)
    }

    @Test
    fun readCappedRejectsOversizedBodies() {
        val body = ByteArray(8) { it.toByte() }
        assertEquals(8, readCapped(ByteArrayInputStream(body), 8).size)

        try {
            readCapped(ByteArrayInputStream(body), 7)
            fail()
        } catch (error: RemoteConfigException) {
            assertEquals(RemoteConfigFailure.TOO_LARGE, error.reason)
        }
    }

    private fun writeFor(writes: List<RemoteSettingWrite>, key: OscPrefKey): RemoteSettingWrite? {
        return writes.find { it.key == key }
    }

    private fun expectFailure(reason: RemoteConfigFailure, block: () -> Unit) {
        try {
            block()
            fail()
        } catch (error: RemoteConfigException) {
            assertEquals(reason, error.reason)
        }
    }
}
