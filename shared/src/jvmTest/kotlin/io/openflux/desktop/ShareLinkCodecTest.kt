package io.openflux.desktop

import io.openflux.desktop.data.JvmShareLinkCodec
import io.openflux.desktop.model.ExtraTransport
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ShareConfig
import io.openflux.desktop.model.ShareTransport
import io.openflux.desktop.model.TransportType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareLinkCodecTest {
    private val codec = JvmShareLinkCodec()

    /** The secret inside [goLink], verbatim: changing it means re-encoding the link in Go. */
    private val goSecret = "0123456789abcdef".repeat(4)

    /** What a real link carries today - 32 bytes, base64, 44 characters. */
    private val secret = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    /** Produced by the core's share.Encode (Go): both sides must read each other's links. */
    private val goLink = "openflux://v1/lI5PSsQwFIevIr91SJP-0TE7EU8hLtLkjRPUtiSvYhm60CN4BE8giCvBM2RuJGVgunb5vff43rdHZ58IBvkjfx9eD29n-T3_5p_8lT8h0NF9z8EywXAcScD1nhwMWstuRx4CiVwkhoHSZVU35xebS9s6T9v_MgQ42i4NfeQEc7sHT8OS9jzZztPLknNsXQdjfITBjnlIpih8SA_yuJNxLEJx1V77m60uKwgMMfQx8ASj1SxOch8iOV7dJ17vGyXgg10-laqSSmpdSa3Mpq4bzHfzXwAAAP__"

    @Test
    fun decodesLinkFromGoCore() {
        val config = codec.decode(goLink)
        assertEquals("Тест Волга", config.name)
        assertTrue(config.negotiate)
        assertEquals("batched", config.codec)
        assertEquals(goSecret, config.secret)
        assertEquals(
            listOf(
                ShareTransport("vyandex", "vyandex", "https://disk.yandex.ru/i/AbCdEf123", 10, ""),
                ShareTransport("direct", "direct", "", 50, "203.0.113.10:8445"),
            ),
            config.transports,
        )
        val profile = Profile.fromShare(config, "p1", 0, ProfileSource.Link)
        assertEquals(TransportType.VYANDEX, profile.transport)
        assertEquals(listOf(TransportType.DIRECT), profile.extras.map { it.type })
        assertEquals("203.0.113.10:8445", profile.extras.single().value)
    }

    @Test
    fun aKeyFromTheGoCorePassesValidation() {
        // The link's secret is 64 hex characters - exactly what provision.NewKey()
        // produces and what the app's own generator writes.
        val profile = Profile.fromShare(codec.decode(goLink), "p1", 0, ProfileSource.Link)
        assertEquals(emptyList(), profile.problems(), "ключ из ссылки ядра должен быть принят")
        assertNull(profile.keyShapeWarning(), "ключ из ссылки ядра не должен вызывать предупреждение")
    }

    @Test
    fun aDamagedKeyIsWarnedAboutButNotRefused() {
        // The core hashes any secret of 16+ characters, so a key that does not
        // look like ours can still be the right one: warning, never a refusal.
        // Refusing would break a node whose key was made by hand.
        val broken = codec.decode(goLink).copy(secret = "a hand written passphrase")
        val profile = Profile.fromShare(broken, "p1", 0, ProfileSource.Link)
        assertEquals(emptyList(), profile.problems())
        assertNotNull(profile.keyShapeWarning(), "битый ключ должен быть замечен")
    }

    @Test
    fun aRealKeyProducesNoProblems() {
        val profile = Profile(
            id = "p",
            name = "Тест Волга",
            transport = TransportType.VYANDEX,
            value = "https://disk.yandex.ru/i/AbCdEf123",
            secret = secret,
            session = true,
            priority = 100,
            extras = listOf(ExtraTransport(TransportType.DIRECT, "203.0.113.10:8445", priority = 50)),
        )
        assertEquals(emptyList(), profile.problems(), "настоящий ключ не должен давать замечаний")
    }

    @Test
    fun roundTripsThroughProfile() {
        val profile = Profile(
            id = "a",
            name = "Дом",
            transport = TransportType.YANDEX,
            value = "https://disk.yandex.ru/i/xyz",
            secret = secret,
            session = true,
            priority = 100,
            extras = listOf(ExtraTransport(TransportType.DIRECT, "1.2.3.4:8445", priority = 50)),
        )
        val link = codec.encode(profile.toShare().getOrThrow())
        assertTrue(link.startsWith("openflux://v1/"))
        val back = Profile.fromShare(codec.decode(link), "b", 0, ProfileSource.Qr)
        assertEquals(profile.copy(id = "b", context = "https://disk.yandex.ru/i/xyz", source = ProfileSource.Qr), back)
    }

    @Test
    fun rejectsBrokenAndForeignLinks() {
        assertFailsWith<IllegalArgumentException> { codec.decode("https://example.com") }
        assertFailsWith<IllegalArgumentException> { codec.decode("openflux://v2/abc") }
        assertFailsWith<IllegalArgumentException> { codec.decode("openflux://v1/!!!") }
        assertFailsWith<IllegalArgumentException> { codec.decode(goLink.dropLast(12)) }
    }

    @Test
    fun rejectsInvalidConfigs() {
        assertFailsWith<IllegalArgumentException> { codec.encode(ShareConfig(transports = emptyList())) }
        // Several transports need a session.
        assertFailsWith<IllegalArgumentException> {
            codec.encode(ShareConfig(transports = listOf(ShareTransport("yandex", url = "https://a"), ShareTransport("mailru", url = "https://b"))))
        }
        // MAX tokens belong to one account and never travel in links.
        assertFailsWith<IllegalArgumentException> { codec.encode(ShareConfig(transports = listOf(ShareTransport("oneme")))) }
    }
}
