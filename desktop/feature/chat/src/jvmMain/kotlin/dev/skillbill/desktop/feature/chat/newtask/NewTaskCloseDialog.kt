package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbDialog
import dev.skillbill.desktop.core.designsystem.component.SbDialogText
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.ui.input.ShellLayer

/** Asks before closing a chat whose [agent] is still replying, the same way the specs screen confirms a cancel. */
@Composable
internal fun NewTaskCloseDialog(title: String, agent: String, onKeep: () -> Unit, onConfirm: () -> Unit) {
  val keepFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { keepFocus.requestFocus() }
  ShellLayer(onKeep)
  SbDialog(
    title = "Close \"$title\"?",
    onDismissRequest = onKeep,
    icon = { CompositionLocalProvider(LocalContentColor provides SkillBillTheme.colors.fg) { SbIcon(LucideIcons.x) } },
    actions = {
      SbButton("Keep open", onKeep, modifier = Modifier.focusRequester(keepFocus), variant = SbButtonVariant.Ghost)
      SbButton("Stop and close", onConfirm, variant = SbButtonVariant.DangerSolid)
    },
  ) {
    SbDialogText("$agent is still replying. Closing stops the reply.")
  }
}
