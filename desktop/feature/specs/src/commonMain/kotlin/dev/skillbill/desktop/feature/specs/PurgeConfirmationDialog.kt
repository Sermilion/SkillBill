package dev.skillbill.desktop.feature.specs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

/** main.js `purge` dialog: trash title icon, intro, the Deleted / Kept ledger, the warning, then Keep run history and Purge. */
@Composable
internal fun PurgeConfirmationDialog(key: String, onAction: (SpecsAction) -> Unit) {
  val keepFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { keepFocus.requestFocus() }
  ShellLayer { onAction(SpecsAction.KeepRunHistory) }
  SbDialog(
    title = "Purge $key?",
    onDismissRequest = { onAction(SpecsAction.KeepRunHistory) },
    icon = { CompositionLocalProvider(LocalContentColor provides SkillBillTheme.colors.fg) { SbIcon(LucideIcons.trash) } },
    actions = {
      SbButton(KEEP_RUN_HISTORY, { onAction(SpecsAction.KeepRunHistory) }, modifier = Modifier.focusRequester(keepFocus), variant = SbButtonVariant.Ghost)
      SbButton("Purge $key", { onAction(SpecsAction.ConfirmPurge) }, variant = SbButtonVariant.DangerSolid)
    },
  ) {
    SbDialogText(PURGE_INTRO)
    Ledger("Deleted", PURGE_DELETED, "Kept", PURGE_KEPT)
    SbDialogText(PURGE_WARNING)
  }
}

/** The Delete spec files confirmation: names the bundle and the folder that goes, with Keep spec files focused first. */
@Composable
internal fun DeleteSpecFilesDialog(target: SpecDeleteTarget, onAction: (SpecsAction) -> Unit) {
  val keepFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { keepFocus.requestFocus() }
  ShellLayer { onAction(SpecsAction.KeepSpecFiles) }
  SbDialog(
    title = deleteTitle(target),
    onDismissRequest = { onAction(SpecsAction.KeepSpecFiles) },
    icon = { CompositionLocalProvider(LocalContentColor provides SkillBillTheme.colors.fg) { SbIcon(LucideIcons.trash) } },
    actions = {
      SbButton(KEEP_SPEC_FILES, { onAction(SpecsAction.KeepSpecFiles) }, modifier = Modifier.focusRequester(keepFocus), variant = SbButtonVariant.Ghost)
      SbButton("Delete spec files", { onAction(SpecsAction.ConfirmDelete) }, variant = SbButtonVariant.DangerSolid)
    },
  ) {
    if (target.issueKey != null) SbDialogText(target.title)
    SbDialogText(DELETE_INTRO)
    SbDialogText(target.folder, style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fg2))
    SbDialogText(DELETE_KEEPS)
    SbDialogText(DELETE_WARNING)
  }
}
