package io.openflux.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.openflux.desktop.service.AppUpdate
import io.openflux.desktop.ui.components.AppDialog
import io.openflux.desktop.ui.theme.AppTheme

/**
 * "There is a new version, here is what changed, update or later".
 *
 * Shown on startup rather than on a button, because the failure it prevents is
 * silent: a phone left on an old build refuses to connect with the same
 * sentence as every other problem, and the user has no reason to go looking
 * for the settings screen.
 *
 * "Позже" is the default, and the dialog can be dismissed by tapping outside -
 * an update prompt that can trap a user into an install is worse than no
 * prompt at all.
 */
@Composable
fun UpdatePrompt(
    update: AppUpdate,
    currentVersion: String,
    downloadHint: String,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppDialog(
        title = "Вышло обновление ${update.version}",
        // Dismissal means "not now", the same as "Позже": BACK and Escape reach
        // onDismiss, and AppDialog has no separate way to say "close without
        // deciding". enterSubmits is off so Enter cannot start a 118 MB
        // download on a dialog the user only glanced at - Ctrl+Enter still can.
        onDismiss = onLater,
        modifier = modifier,
        primary = "Обновить",
        onPrimary = onUpdate,
        secondary = "Позже",
        enterSubmits = false,
    ) {
        val notes = update.notes
        if (notes != null) {
            ReleaseNotesBlock(notes, Modifier.fillMaxWidth())
            Spacer(Modifier.height(AppTheme.spacing.m))
        }
        // What pressing it costs. A 118 MB download with no warning is the other
        // way this prompt can lose the user's trust, and on a phone it may be
        // metered data. The caller passes the real figure: it knows which file
        // is being offered and whether the network is mobile.
        Column(Modifier.fillMaxWidth()) {
            Text(
                "Текущая версия: $currentVersion",
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.textSecondary,
            )
            Text(
                downloadHint,
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.textSecondary,
            )
        }
    }
}