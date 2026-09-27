package kittoku.osc.preference

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.URI


class RemoteConfigTest {
    @Test
    fun partialJsonWritesOnlyPresentKeys() {
        val profile = decodeRemoteProfile(
            """
            {
              "stringSetting": {"HOME_HOSTNAME": "vpn.example.com"},
              "intSetting": {"SSL_PORT": 8443}
            }
            """.trimIndent().toByteArray()
        )

        val writes = remoteSettingWrites(profile)

        assertEquals(
            listOf(
                RemoteSettingWrite.IntVal(OscPrefKey.SSL_PORT, 8443),
                RemoteSettingWrite.Str(OscPrefKey.HOME_HOSTNAME, "vpn.example.com"),
            ),
            writes,
        )
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

        assertEquals(
            listOf(
                RemoteSettingWrite.Bool(OscPrefKey.REMOTE_CONFIG_ENABLED, true),
                RemoteSettingWrite.Str(OscPrefKey.HOME_USERNAME, "alice"),
                RemoteSettingWrite.Str(OscPrefKey.REMOTE_CONFIG_URL, "https://vpn.example.com/config.json"),
            ),
            remoteSettingWrites(profile),
        )
    }

    @Test
    fun remoteConfigUrlMustStayHttps() {
        val profile = Profile(
            stringSetting = mutableMapOf(
                OscPrefKey.REMOTE_CONFIG_URL.name to "http://vpn.example.com/config.json",
                OscPrefKey.HOME_HOSTNAME.name to "vpn.example.com",
            ),
        )

        assertEquals(
            listOf(RemoteSettingWrite.Str(OscPrefKey.HOME_HOSTNAME, "vpn.example.com")),
            remoteSettingWrites(profile),
        )
    }

    @Test
    fun blankRemoteConfigUrlClearsTheAddress() {
        val profile = Profile(
            stringSetting = mutableMapOf(OscPrefKey.REMOTE_CONFIG_URL.name to "  "),
        )

        assertEquals(
            listOf(RemoteSettingWrite.Str(OscPrefKey.REMOTE_CONFIG_URL, "")),
            remoteSettingWrites(profile),
        )
    }

    @Test
    fun emptyStringIsAnExplicitValue() {
        val profile = Profile(
            stringSetting = mutableMapOf(OscPrefKey.HOME_PASSWORD.name to ""),
        )

        assertEquals(
            listOf(RemoteSettingWrite.Str(OscPrefKey.HOME_PASSWORD, "")),
            remoteSettingWrites(profile),
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
            listOf(RemoteSettingWrite.Str(OscPrefKey.HOME_HOSTNAME, "vpn.example.com")),
            writes,
        )
    }

    @Test
    fun extraJsonFieldIsIgnored() {
        val profile = decodeRemoteProfile(
            """{"comment":"hi","stringSetting":{"HOME_HOSTNAME":"vpn.example.com"}}""".toByteArray()
        )

        assertEquals(
            listOf(RemoteSettingWrite.Str(OscPrefKey.HOME_HOSTNAME, "vpn.example.com")),
            remoteSettingWrites(profile),
        )
    }

    @Test
    fun utf8BomIsAccepted() {
        val body = "\uFEFF{\"stringSetting\":{\"HOME_HOSTNAME\":\"vpn.example.com\"}}".toByteArray()
        val writes = remoteSettingWrites(decodeRemoteProfile(body))

        assertEquals(
            listOf(RemoteSettingWrite.Str(OscPrefKey.HOME_HOSTNAME, "vpn.example.com")),
            writes,
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

    @Test
    fun profileLinkDecodesTheHttpsUrl() {
        assertEquals(
            "https://example.com/vpn.json",
            parseProfileLink("osc://profile?url=https%3A%2F%2Fexample.com%2Fvpn.json"),
        )
        assertNull(parseProfileLink("osc://profile?url=http%3A%2F%2Fexample.com%2Fvpn.json"))
        assertNull(parseProfileLink("https://example.com/vpn.json"))
        assertNull(parseProfileLink("osc://other?url=https%3A%2F%2Fexample.com%2Fvpn.json"))
    }

    @Test
    fun profileLinkRoundTripsUrlSafeBase64() {
        val plain = "https://example.com/vpn.json"
        val link = profileLinkFor(plain)

        assertEquals("osc://profile?url=aHR0cHM6Ly9leGFtcGxlLmNvbS92cG4uanNvbg", link)
        assertEquals(plain, parseProfileLink(link))

        val nested = "https://example.com/vpn.json?token=a+b&x=1#frag"
        val nestedLink = profileLinkFor(nested)
        assertEquals(nested, parseProfileLink(nestedLink))
        assertTrue(nestedLink.matches(Regex("osc://profile\\?url=[A-Za-z0-9_-]+")))
        assertTrue(!nestedLink.contains("token"))
        assertTrue(!nestedLink.contains("frag"))

        assertEquals(
            "https://example.com/~user/vpn.json",
            parseProfileLink("osc://profile?url=aHR0cHM6Ly9leGFtcGxlLmNvbS9+dXNlci92cG4uanNvbg=="),
        )
        assertEquals(
            "https://cdn.example.com/a/b.json?q=1",
            parseProfileLink("osc://profile?url=aHR0cHM6Ly9jZG4uZXhhbXBsZS5jb20vYS9iLmpzb24/cT0x"),
        )
        assertNull(parseProfileLink("osc://profile?url=aHR0cDovL2V4YW1wbGUuY29tL3Zwbi5qc29u"))
    }

    @Test
    fun newProfileKeepsTheLinkWithoutReplacingOpenSettings() {
        val fromFile = Profile(
            stringSetting = mutableMapOf(
                OscPrefKey.HOME_HOSTNAME.name to "vpn.example.com",
                OscPrefKey.REMOTE_CONFIG_URL.name to "http://evil.example/next.json",
            ),
            booleanSetting = mutableMapOf(OscPrefKey.REMOTE_CONFIG_ENABLED.name to false),
        )

        val profile = profileFromRemoteLink(fromFile, "https://example.com/vpn.json")

        assertEquals("vpn.example.com", profile.stringSetting[OscPrefKey.HOME_HOSTNAME.name])
        assertEquals("https://example.com/vpn.json", profile.stringSetting[OscPrefKey.REMOTE_CONFIG_URL.name])
        assertEquals(true, profile.booleanSetting[OscPrefKey.REMOTE_CONFIG_ENABLED.name])
    }

    @Test
    fun newProfileUsesHttpsUrlFromTheFile() {
        val fromFile = Profile(
            stringSetting = mutableMapOf(
                OscPrefKey.REMOTE_CONFIG_URL.name to "https://cdn.example.com/next.json",
            ),
        )

        val profile = profileFromRemoteLink(fromFile, "https://example.com/vpn.json")

        assertEquals("https://cdn.example.com/next.json", profile.stringSetting[OscPrefKey.REMOTE_CONFIG_URL.name])
        assertEquals(true, profile.booleanSetting[OscPrefKey.REMOTE_CONFIG_ENABLED.name])
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

    private fun expectFailure(reason: RemoteConfigFailure, block: () -> Unit) {
        try {
            block()
            fail()
        } catch (error: RemoteConfigException) {
            assertEquals(reason, error.reason)
        }
    }
}
