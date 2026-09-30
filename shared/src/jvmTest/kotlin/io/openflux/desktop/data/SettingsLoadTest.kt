package io.openflux.desktop.data

import io.openflux.desktop.model.AppSettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a settings file from an older version does when the current build reads
 * it.
 *
 * Two failures hid behind one null. A field the file did not have took its
 * *declared* default rather than the platform's - which silently dropped
 * Android out of VPN mode into a SOCKS5-only proxy after an update, with the
 * phone's traffic simply not going anywhere. And a file that could not be
 * decoded at all came back as "nothing saved", so the next save wrote that
 * emptiness over the user's profiles and channel keys.
 */
class SettingsLoadTest {

    private fun dir() = Files.createTempDirectory("openflux-settings").toFile()

    @Test
    fun `the platform default survives a file that predates the field`() {
        val d = dir()
        d.resolve("settings.json").writeText("""{"socksPort": 1080}""")

        val loaded = FileSettingsRepository(d, defaults = AppSettings(fullTunnel = true)).settings.value

        assertTrue(
            loaded.fullTunnel,
            "ключа fullTunnel в файле нет - должно взять значение платформы, а не объявленный false",
        )
        assertEquals(1080, loaded.socksPort, "а сохранённое значение должно выигрывать у дефолта")
    }

    @Test
    fun `a value the user chose is not overwritten by the platform default`() {
        val d = dir()
        d.resolve("settings.json").writeText("""{"socksPort": 1080, "fullTunnel": false}""")

        val loaded = FileSettingsRepository(d, defaults = AppSettings(fullTunnel = true)).settings.value

        assertFalse(loaded.fullTunnel, "пользователь выключил режим сам - дефолт платформы не должен его перебивать")
    }

    @Test
    fun `an absent file is simply the defaults`() {
        val loaded = FileSettingsRepository(dir(), defaults = AppSettings(fullTunnel = true)).settings.value
        assertTrue(loaded.fullTunnel)
    }

    @Test
    fun `an unreadable profiles file is kept and not overwritten`() {
        val d = dir()
        // One value this build does not know: the whole list used to come back
        // empty, and the next save wrote that empty list over the file.
        d.resolve("profiles.json").writeText("""[{"id":"a","name":"Узел","transport":"a-transport-that-no-longer-exists"}]""")

        val repo = FileProfileRepository(d)
        assertNotNull(repo.unreadable, "о нечитаемом файле пользователь должен узнать, а не увидеть пустой список")
        assertTrue(repo.profiles.value.isEmpty())

        repo.delete("anything")
        assertTrue(
            d.resolve("profiles.json").readText().contains("no-longer-exists"),
            "файл с профилями нельзя затирать, пока он не читается",
        )
        assertNotNull(d.resolve("profiles.json.bad"), "исходник должен остаться для разбора")
    }

    @Test
    fun `a readable profiles file behaves normally`() {
        val d = dir()
        d.resolve("profiles.json").writeText("""[]""")
        val repo = FileProfileRepository(d)
        assertEquals(null, repo.unreadable)
    }

    @Test
    fun `ports outside the usable range are pulled back in`() {
        val d = dir()
        // socksPort + 1 is where the HTTP proxy is bound, so 65535 asked for
        // 127.0.0.1:65536 and the core refused to start at all.
        d.resolve("settings.json").writeText("""{"socksPort": 65535}""")
        val loaded = FileSettingsRepository(d).settings.value
        assertTrue(loaded.socksPort in 1024..65534, "получилось ${loaded.socksPort}")
    }
}
