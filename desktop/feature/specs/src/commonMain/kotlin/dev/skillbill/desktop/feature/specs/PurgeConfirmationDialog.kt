package dev.skillbill.desktop.feature.specs

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Ledger
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbDialog
import dev.skillbill.desktop.core.designsystem.component.SbDialogText
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.ui.input.ShellLayer

@Composable
internal fun SpecConfirmationDialog(
  title: String,
  icon: ImageVector,
  keepLabel: String,
  confirmLabel: String,
  onKeep: () -> Unit,
  onConfirm: () -> Unit,
  body: @Composable ColumnScope.() -> Unit,
) {
  val keepFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { keepFocus.requestFocus() }
  ShellLayer(onKeep)
  SbDialog(
    title = title,
    onDismissRequest = onKeep,
    icon = { CompositionLocalProvider(LocalContentColor provides SkillBillTheme.colors.fg) { SbIcon(icon) } },
    actions = {
      SbButton(keepLabel, onKeep, modifier = Modifier.focusRequester(keepFocus), variant = SbButtonVariant.Ghost)
      SbButton(confirmLabel, onConfirm, variant = SbButtonVariant.DangerSolid)
    },
    body = body,
  )
}

@Composable
internal fun CancelConfirmationDialog(key: String, onAction: (SpecsAction) -> Unit) {
  SpecConfirmationDialog(
    title = cancelPrompt(key),
    icon = LucideIcons.x,
    keepLabel = KEEP_RUNNING,
    confirmLabel = CANCEL_GOAL,
    onKeep = { onAction(SpecsAction.KeepRunning) },
    onConfirm = { onAction(SpecsAction.ConfirmCancel) },
  ) {
    SbDialogText("The run stops and the goal is marked canceled. Its branch and commits stay.")
  }
}

@Composable
internal fun PurgeConfirmationDialog(key: String, onAction: (SpecsAction) -> Unit) {
  SpecConfirmationDialog(
    title = "Purge $key run history?",
    icon = LucideIcons.reset,
    keepLabel = KEEP_RUN_HISTORY,
    confirmLabel = "Purge $key",
    onKeep = { onAction(SpecsAction.KeepRunHistory) },
    onConfirm = { onAction(SpecsAction.ConfirmPurge) },
  ) {
    SbDialogText(PURGE_INTRO)
    Ledger("Deleted", PURGE_DELETED, "Kept", PURGE_KEPT)
    SbDialogText(PURGE_WARNING)
  }
}

@Composable
internal fun DeleteSpecFilesDialog(target: SpecDeleteTarget, onAction: (SpecsAction) -> Unit) {
  SpecConfirmationDialog(
    title = deleteTitle(target),
    icon = LucideIcons.trash,
    keepLabel = KEEP_SPEC_FILES,
    confirmLabel = "Delete spec files",
    onKeep = { onAction(SpecsAction.KeepSpecFiles) },
    onConfirm = { onAction(SpecsAction.ConfirmDelete) },
  ) {
    if (target.issueKey != null) SbDialogText(target.title)
    SbDialogText(DELETE_INTRO)
    SbDialogText(target.folder, style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fg2))
    SbDialogText(DELETE_KEEPS)
    SbDialogText(DELETE_WARNING)
  }
}
