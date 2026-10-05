package io.openflux.desktop.data

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A corrupt settings.json must survive the user changing anything.
 *
 * It used to be read as "no settings" and the first edit wrote the defaults
 * back over it. What that costs: the exit server host, the SOCKS port, and the
 * master keys of every node of the user's own - the things that make a
 * reinstall usable rather than a fresh start. The copy to .bad was already
 * being made, so the data was never really lost; it just stopped being the
 * live file, silently, and the user had no way to tell.
 *
 * FileProfileRepository has refused saves in this situation for a while.
 * Settings now does the same, and this is what holds it there.
 */
class SettingsCorruptionTest {

    @Test
    fun `a corrupt settings file is kept and saves are refused`() {
        val dir = createTempDirectory(prefix = "of-settings").toFile()
        val file = File(dir, "settings.json")
        file.writeText("{ this is not json")
        val original = file.readText()

        val repo = FileSettingsRepository(dir)

        assertNotNull(repo.unreadable, "a corrupt file was reported as normal")

        // Whatever the user does now, the damaged file must still be there.
        repo.update { it.copy(socksPort = 9999) }
        repo.update { it.copy(exitShareHost = "example.invalid") }

        assertEquals(original, file.readText(), "the corrupt file was overwritten")
        // The change applies to the running app - refusing that too would mean
        // a user could not switch the theme until they found the file - but it
        // is not written anywhere, because there is nowhere safe to write it.
        assertEquals(9999, repo.settings.value.socksPort, "the running app ignored the change")
        assertEquals("example.invalid", repo.settings.value.exitShareHost)
        assertTrue(File(dir, "settings.json.bad").exists(), "no .bad copy was left")

        dir.deleteRecursively()
    }

    @Test
    fun `a readable settings file still saves`() {
        val dir = createTempDirectory(prefix = "of-settings-ok").toFile()
        val repo = FileSettingsRepository(dir)
        assertEquals(null, repo.unreadable)

        repo.update { it.copy(socksPort = 9999) }
        assertEquals(9999, repo.settings.value.socksPort)
        assertTrue(File(dir, "settings.json").readText().contains("9999"), "the change was not persisted")

        dir.deleteRecursively()
    }
}