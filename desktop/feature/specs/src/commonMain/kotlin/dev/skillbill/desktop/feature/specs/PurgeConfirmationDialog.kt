package dev.skillbill.desktop.feature.specs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.Ledger
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbDialog
import dev.skillbill.desktop.core.designsystem.component.SbDialogText
import dev.skillbill.desktop.core.ui.input.ShellLayer

@Composable
internal fun PurgeConfirmationDialog(key: String, onAction: (SpecsAction) -> Unit) {
  val keepFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { keepFocus.requestFocus() }
  ShellLayer { onAction(SpecsAction.KeepRunHistory) }
  SbDialog(
    title = "Purge $key?",
    onDismissRequest = { onAction(SpecsAction.KeepRunHistory) },
    actions = {
      SbButton(KEEP_RUN_HISTORY, { onAction(SpecsAction.KeepRunHistory) }, modifier = Modifier.focusRequester(keepFocus), size = SbControlSize.Small, variant = SbButtonVariant.Ghost)
      SbButton("Purge $key", { onAction(SpecsAction.ConfirmPurge) }, size = SbControlSize.Small, variant = SbButtonVariant.DangerSolid)
    },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      SbDialogText(PURGE_INTRO)
      Ledger("Deleted", PURGE_DELETED, "Kept", PURGE_KEPT)
      Hint(PURGE_WARNING, FeedbackTone.Warn)
    }
  }
}
