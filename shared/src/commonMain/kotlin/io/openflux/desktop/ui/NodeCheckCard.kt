package io.openflux.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.openflux.desktop.service.NodeCheckResult
import io.openflux.desktop.service.NodeCheckStage
import io.openflux.desktop.ui.components.AppCard
import io.openflux.desktop.ui.components.AppButton
import io.openflux.desktop.ui.components.StatusBadge
import io.openflux.desktop.ui.components.Tone
import io.openflux.desktop.ui.theme.AppTheme

/**
 * The answer to "почему не подключается", drawn as the path that was walked.
 *
 * A single sentence cannot carry this: "не подключилось" covers a wrong key, a
 * document that grants no access, an old build the server refuses, and no
 * internet at all. Listing the stages crossed off before the failure is what
 * separates them without the user reading the log, which on a phone is not an
 * option - there is no adb and no core log.
 *
 * It READS the log of the last attempt; it does not start a new one. The title
 * says so, because a button named "проверить связь" is understood to try
 * something, and a verdict from an hour-old attempt is worse than no verdict.
 */
@Composable
fun NodeCheckCard(
    result: NodeCheckResult,
    checking: Boolean,
    onRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppCard(modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(AppTheme.spacing.l),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Где остановилось подключение",
                style = AppTheme.typography.sectionTitle,
                color = AppTheme.colors.text,
            )
            StatusBadge(
                when {
                    checking -> "проверяю"
                    result.ok -> "связь есть"
                    // Nothing was proven and nothing was disproved: the caller
                    // checked before the core had time to say anything. Calling
                    // that a problem sends the user to fix a node that works.
                    result.problem == null -> "неясно"
                    else -> "проблема"
                },
                when {
                    checking -> Tone.Neutral
                    result.ok -> Tone.Success
                    result.problem == null -> Tone.Neutral
                    else -> Tone.Warning
                },
            )
        }

        // analyze() deliberately invents no cause when it has none, so the
        // card has to say something of its own: a badge alone reads as a verdict
        // without a reason behind it.
        if (!result.ok) {
            Text(
                result.problem ?: "В журнале ядра ошибки нет. Нажмите «Подключить» ещё раз, потом эту кнопку.",
                style = AppTheme.typography.body,
                color = AppTheme.colors.text,
                modifier = Modifier.padding(horizontal = AppTheme.spacing.l),
            )
        }

        // Every stage, crossed off or not. Showing the ones that were passed is
        // the point: a user who sees "Ключ принят" knows the key was accepted
        // and the problem is downstream of it, which is the single thing that
        // separates "не тот ключ" from "нода не запущена".
        Column(
            Modifier.padding(AppTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs),
        ) {
            NodeCheckStage.entries.forEach { stage ->
                val done = result.reached.contains(stage)
                Text(
                    (if (done) "✓ " else "·  ") + stage.label,
                    style = AppTheme.typography.bodySmall,
                    color = if (done) AppTheme.colors.textSecondary else AppTheme.colors.textSecondary.copy(alpha = 0.55f),
                )
            }
        }

        AppButton(
            if (checking) "Смотрю…" else "Проверить ещё раз",
            onRun,
            enabled = !checking,
        )
    }
}