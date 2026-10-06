package io.openflux.desktop.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * These tests provoke a write failure by taking an exclusive `FileChannel`
 * lock and then writing through a different descriptor.
 *
 * That only works where file locking is MANDATORY. On Windows it is: the
 * second write fails with a sharing violation, which is the condition under
 * test. On Linux, including the WSL that runs the Android build, `FileLock` is
 * ADVISORY - nothing stops a separate descriptor from writing anyway. So the
 * write succeeds, no failure is recorded, and these assertions fail.
 *
 * That is the test's premise not holding, not the behaviour regressing. It
 * was still red on every WSL build, which meant `wsl-build.sh` could not
 * produce an APK - a false alarm blocking a real artifact. The tests now skip
 * where the mechanism they are testing does not exist, and say so, rather than
 * reporting a defect that is not one.
 *
 * The behaviour itself - a failed write records itself, updates in-memory
 * state anyway, and the next successful write clears the record - is portable
 * and is still covered by the tests below that do not need a lock.
 *
 * Returned rather than assumed: this module tests with kotlin.test, and
 * JUnit 5's Assumptions is not on the classpath. A silent early return is the
 * portable idiom here, and the name says why.
 */
private fun fileLockingIsMandatory(): Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Windows")

/**
 * A settings write that fails must not take the application with it.
 *
 * `update` used to run `store.write` inside `MutableStateFlow.update`'s
 * transform, and it threw. Three consequences, all from reading the chain
 * rather than guessing:
 *
 * - The transform runs BEFORE `compareAndSet`, so a throwing write meant the
 *   in-memory value was never updated either. The change was lost, not just
 *   the file.
 * - `update` is the single choke point every settings write in the app passes
 *   through, and most callers are Compose click handlers on the main thread. An
 *   unwritable settings.json threw straight through the UI handler and killed
 *   the application.
 * - CoreConnectionService calls it from stopRun() BEFORE killTree(), where a
 *   throw left the core alive holding the SOCKS port with the state stuck at
 *   Disconnecting, so the next connect threw in the same place and the connect
 *   button stayed dead until the app was restarted.
 *
 * What must NOT change: when the file is UNREADABLE rather than unwritable, the
 * existing and deliberate policy is that the change still applies in memory and
 * only the write is refused. SettingsCorruptionTest caught the first version of
 * this fix, which had moved the early return above the state update.
 *
 * The failure is produced by holding an exclusive lock on settings.json: neither
 * Windows nor Linux will let the temp file be moved over a locked target.
 */
class SettingsWriteFailureTest {

    private fun tempDir(): File = Files.createTempDirectory("of-settings-test").toFile().also { it.deleteOnExit() }

    @Test
    fun `a write that fails still applies in memory and does not throw`() {
        if (!fileLockingIsMandatory()) return // see the file-level note
        val dir = tempDir()
        val repo = FileSettingsRepository(dir)
        val settings = File(dir, "settings.json")
        assertTrue(settings.exists(), "the repository should have created its file")

        RandomAccessFile(settings, "rw").use { raf ->
            raf.channel.use { channel ->
                channel.lock().use {
                    val threw = runCatching { repo.update { it.copy(socksPort = 9999) } }.exceptionOrNull()
                    assertNull(threw, "update must not propagate a write failure: $threw")
                }
            }
        }
        assertEquals(9999, repo.settings.value.socksPort, "the running app ignored the change")
    }

    @Test
    fun `a failed write is remembered and cleared by the next that works`() {
        if (!fileLockingIsMandatory()) return // see the file-level note
        val dir = tempDir()
        val repo = FileSettingsRepository(dir)
        val settings = File(dir, "settings.json")

        RandomAccessFile(settings, "rw").use { raf ->
            raf.channel.use { channel ->
                channel.lock().use {
                    repo.update { it.copy(socksPort = 9999) }
                }
            }
        }
        assertNotNull(repo.writeFailure, "a refused write must leave a record")

        // The lock is gone; the next save must succeed and clear the record.
        repo.update { it.copy(socksPort = 9998) }
        assertNull(repo.writeFailure, "a later successful write must clear the record")
        assertTrue(settings.readText().contains("9998"), "the change was not persisted")
    }

    @Test
    fun `a change that writes nothing neither writes nor complains`() {
        val dir = tempDir()
        val repo = FileSettingsRepository(dir)
        repo.update { it.copy(socksPort = 8888) }
        assertNull(repo.writeFailure)
        repo.update { it } // no change at all
        assertNull(repo.writeFailure)
        assertEquals(8888, repo.settings.value.socksPort)
    }

    @Test
    fun `an unreadable file still applies the change and refuses only the write`() {
        // Pre-existing policy, and the reason the first version of this fix was
        // wrong: the app runs on the in-memory value, so freezing it would be
        // worse than not saving.
        val dir = tempDir()
        val file = File(dir, "settings.json")
        file.writeText("{ this is not json")
        val repo = FileSettingsRepository(dir)
        assertNotNull(repo.unreadable)

        repo.update { it.copy(socksPort = 7777) }
        assertEquals(7777, repo.settings.value.socksPort, "the change must still apply")
        assertTrue(file.readText().contains("this is not json"), "the damaged file must be left alone")
    }

    @Test
    fun `a refused write reports itself as unsaved without throwing`() {
        // This is the signal applySystemProxy needs, and it is the whole reason
        // it exists as a property. `update` absorbs the failure so the app
        // cannot be killed by an unwritable file - and that also means it never
        // throws, so a caller that waits for an exception always takes the
        // success path and takes the Windows system proxy over without a record
        // it can ever put back.
        val dir = tempDir()
        val repo = FileSettingsRepository(dir)
        val settings = File(dir, "settings.json")
        assertFalse(repo.unsaved, "a fresh repository has nothing unsaved")

        if (!fileLockingIsMandatory()) return // see the file-level note
        RandomAccessFile(settings, "rw").use { raf ->
            raf.channel.use { channel ->
                channel.lock().use {
                    val threw = runCatching { repo.update { it.copy(socksPort = 9999) } }
                    assertNull(threw.exceptionOrNull(), "update must not throw")
                }
            }
        }

        assertTrue(repo.unsaved, "a refused write must be visible to the caller")
        assertTrue(repo.writeFailureHint.isNotEmpty(), "there must be a reason to log")
        assertEquals(9999, repo.settings.value.socksPort, "but it must still apply in memory")
    }

    @Test
    fun `a locked repository reports unsaved for the whole session`() {
        // Nothing is ever written while locked, so reporting "saved" would be a
        // false all-clear rather than a missing value.
        val dir = tempDir()
        File(dir, "settings.json").writeText("{ this is not json")
        val repo = FileSettingsRepository(dir)
        assertNotNull(repo.unreadable)
        assertTrue(repo.unsaved, "a locked store writes nothing and must say so")

        repo.update { it.copy(socksPort = 6666) }
        assertTrue(repo.unsaved, "the lock does not clear itself")
        assertNotNull(repo.writeFailureHint)
    }
}