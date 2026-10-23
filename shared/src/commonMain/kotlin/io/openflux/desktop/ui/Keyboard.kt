package io.openflux.desktop.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much of the window the software keyboard covers.
 *
 * A dialog cannot measure this itself: the keyboard does not resize a dialog
 * window and does not report its inset to the dialog's content, so the covered
 * height has to come from the activity's own composition, where the insets are
 * real. Without it the buttons at the bottom of a dialog - "Добавить профиль" in
 * the import one - end up under the keyboard, unreachable while it is up.
 *
 * The default is for the desktop, which has no keyboard over its windows.
 */
val LocalKeyboardOverlap = compositionLocalOf<Dp> { 0.dp }