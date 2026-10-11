package io.openflux.desktop.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.service.AppUpdate
import io.openflux.desktop.service.InstallResult
import io.openflux.desktop.service.PlatformKind
import io.openflux.desktop.service.UpdateCheck
import io.openflux.desktop.ui.UpdatePrompt
import io.openflux.desktop.ui.components.LocalToaster
import io.openflux.desktop.ui.components.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** How long "Позже" silences the same version. */
private const val DEFER_DAYS = 3L
private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * Checks for a new release once per start and offers it.
 *
 * The check is off the critical path: it runs in a coroutine that the shell
 * does not wait for, so a slow GitHub cannot delay the window or the phone
 * becoming usable. A check that fails says nothing at all - an app that greets
 * a user with an error because a version server was briefly unreachable is
 * worse than an app that never mentions it.
 */
@Composable
fun UpdateWatcher(container: AppContainer, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var offered by remember { mutableStateOf<AppUpdate?>(null) }

    // Keyed on the switch itself, not on Unit: with Unit, turning the check ON
    // mid-session did nothing until the app was restarted, and the user has no
    // way to tell "выключено" from "сломано".
    LaunchedEffect(settings.autoCheckUpdates) {
        if (!settings.autoCheckUpdates) return@LaunchedEffect
        // Never swap the update behind a dialog the user is looking at.
        if (offered != null) return@LaunchedEffect
        val result = runCatching { container.platform.checkForUpdateDetailed() }.getOrNull() ?: return@LaunchedEffect
        val update = (result as? UpdateCheck.Available)?.update ?: return@LaunchedEffect
        if (!update.newer) return@LaunchedEffect
        // Deferred long enough that it can still come back. Without this,
        // "Позже" is permanent and the release is never offered again.
        if (settings.deferredUpdateVersion == update.version &&
            container.platform.now() - settings.deferredUpdateAt < DEFER_DAYS * DAY_MS
        ) {
            return@LaunchedEffect
        }
        offered = update
    }

    val pending = offered ?: return
    UpdatePrompt(
        update = pending,
        currentVersion = container.platform.appVersion,
        downloadHint = if (container.platform.kind == PlatformKind.Desktop) {
            "Обновление на компьютер: около 120 МБ"
        } else {
            "Обновление на телефон: около 19 МБ"
        },
        onUpdate = {
            offered = null
            scope.launch {
                // The shell has no progress bar of its own - it is drawn on the
                // settings screen - so the outcome has to be said out loud. It
                // must also be said by RESULT: `Refused(shown = true)` means
                // the platform already explained itself (the user answered "Нет"
                // to the Windows dialog, or Android named the missing
                // permission), and a blanket "скачивание и установка…" on top
                // of that overwrites the only sentence that was correct.
                val result = runCatching { container.platform.installUpdate(pending) }
                    .getOrElse { error ->
                        if (error is CancellationException) throw error
                        toaster.show("Не удалось обновить: ${error.message ?: error.javaClass.simpleName}", Tone.Danger)
                        return@launch
                    }
                when {
                    result is InstallResult.HandedOff && !result.verified -> toaster.show(
                        "Файл установщика не сверился с контрольной суммой: в выпуске она не опубликована",
                        Tone.Warning,
                    )
                    result is InstallResult.HandedOff -> toaster.show("OpenFlux ${pending.version}: установка…")
                    result is InstallResult.Refused && !result.shown ->
                        toaster.show("Не удалось загрузить обновление", Tone.Danger)
                }
            }
        },
        onLater = {
            offered = null
            container.settings.update {
                it.copy(deferredUpdateVersion = pending.version, deferredUpdateAt = container.platform.now())
            }
        },
    )
}