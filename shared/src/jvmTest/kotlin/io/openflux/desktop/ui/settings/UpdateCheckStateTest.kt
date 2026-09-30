package io.openflux.desktop.ui.settings

import io.openflux.desktop.service.AppUpdate
import io.openflux.desktop.service.UpdateCheck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a check result means for the settings screen.
 *
 * A user pressed "Проверить обновления" on 2.2.0, was told the latest release
 * was "не найден", and had no way to tell a check that worked from one that
 * never reached GitHub - neither did the person reading the report. Two of the
 * three outcomes arrived as the same null and the screen drew them the same
 * way. These hold them apart.
 */
class UpdateCheckStateTest {

    private val update = AppUpdate(
        version = "2.3.0",
        downloadUrl = "https://github.com/imbazyx/OpenFlux/releases/download/v2.3.0/OpenFlux-2.3.0.msi",
        versionCode = 20300,
        newer = true,
    )

    @Test
    fun `a newer release is offered, with no problem attached`() {
        val view = UpdateView().apply(UpdateCheck.Available(update))
        assertEquals(update, view.update)
        assertFalse(view.failed)
        assertNull(view.problem)
        assertNull(view.latest)
    }

    @Test
    fun `being up to date is not being broken`() {
        val view = UpdateView().apply(UpdateCheck.UpToDate("2.2.0"))
        assertNull(view.update)
        assertFalse(view.failed, "проверка удалась, значит это не отказ")
        assertNull(view.problem, "успешной проверке не нужна причина")
        assertEquals("2.2.0", view.latest, "экран должен показать, что именно свежее")
    }

    @Test
    fun `a failed check says why, and does not claim a release is missing`() {
        val view = UpdateView().apply(
            UpdateCheck.Failed("GitHub временно не отвечает (лимит запросов с одного адреса)")
        )
        assertNull(view.update)
        assertTrue(view.failed)
        assertNotNull(view.problem, "без причины «не проверить» не значит ничего")
        assertNull(view.latest, "неизвестно, что там опубликовано, - не выдумывать версию")
    }

    @Test
    fun `a failure after a successful check replaces the old answer`() {
        val first = UpdateView().apply(UpdateCheck.UpToDate("2.2.0"))
        val second = first.apply(UpdateCheck.Failed("нет связи с интернетом"))
        assertTrue(second.failed)
        assertEquals("нет связи с интернетом", second.problem)
        // The version found earlier is still true, but the row now says the
        // latest could not be re-confirmed rather than claiming a fresh result.
        assertEquals("2.2.0", second.latest)
    }

    @Test
    fun `a later success clears a previous failure`() {
        val first = UpdateView().apply(UpdateCheck.Failed("прошлая ошибка"))
        val second = first.apply(UpdateCheck.Available(update))
        assertFalse(second.failed)
        assertNull(second.problem, "старая причина не должна висеть после успеха")
        assertEquals(update, second.update)
    }
}
