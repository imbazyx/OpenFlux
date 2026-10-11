package io.openflux.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import io.openflux.desktop.ui.theme.AppTheme

/**
 * A download bar, or an honest "I cannot tell how far along this is" when the
 * server sent no length.
 *
 * The indeterminate form is not a fallback for a bug. GitHub serves releases
 * with Content-Length and it is normally there, but a proxy or a captive
 * portal in between may strip it, and the honest drawing of a transfer of
 * unknown length is a moving bar with a byte count next to it - not a
 * percentage stuck at zero that says the download failed while it is in fact
 * arriving, which is the confusion this bar was added to remove.
 */
@Composable
fun DownloadProgressBar(done: Long, total: Long, modifier: Modifier = Modifier) {
    val fraction = downloadProgressFraction(done, total)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        // Two separate calls rather than one call with an `indeterminate` flag:
        // this Material version expresses the difference as two overloads, and
        // picking between them here also keeps the rule in one place - a
        // transfer of known length always draws its real share.
        if (fraction == null) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = AppTheme.colors.accent,
            )
        } else {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = AppTheme.colors.accent,
            )
        }
        Text(
            downloadProgressText(done, total),
            style = AppTheme.typography.bodySmall,
            color = AppTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * What the release says changed, before the user commits to the transfer.
 *
 * Whole, not capped. It used to stop at eight lines with an ellipsis, on the
 * reasoning that a long note would push the buttons off a phone screen - but
 * the buttons live outside this block, in the dialog's own scrollable body, and
 * the result was a release note that silently lost its last lines, which is the
 * part people read.
 */
@Composable
fun ReleaseNotesBlock(notes: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
        Text("Что изменилось", style = AppTheme.typography.sectionTitle, color = AppTheme.colors.text)
        Text(
            notes,
            style = AppTheme.typography.bodySmall,
            color = AppTheme.colors.textSecondary,
        )
    }
}