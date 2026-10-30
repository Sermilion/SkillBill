package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbDialog
import dev.skillbill.desktop.core.designsystem.detail.PropertyList
import dev.skillbill.desktop.core.designsystem.detail.PropertyRow
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.RecoveryPreview
import dev.skillbill.desktop.core.ui.input.ShellLayer

/** The recovery preview the user reviews before "Apply and resume"; focus returns to the Recover button when it closes. */
@Composable
internal fun RecoveryDialog(preview: RecoveryPreview, applying: Boolean, returnFocusTo: FocusRequester, onApply: () -> Unit, onDismiss: () -> Unit) {
  ShellLayer(onDismiss)
  SbDialog(
    title = preview.label,
    onDismissRequest = onDismiss,
    icon = { SbIcon(LucideIcons.refresh) },
    returnFocusTo = returnFocusTo,
    actions = {
      SbButton("Cancel", onDismiss, variant = SbButtonVariant.Ghost, enabled = !applying)
      SbButton(if (applying) WORKING else "Apply and resume", onApply, variant = SbButtonVariant.Primary, enabled = !applying)
    },
  ) {
    PropertyList {
      preview.effects?.let { PropertyRow("Effects") { BasicText(it, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2)) } }
      preview.targetWorkflowId?.let { PropertyRow("Workflow") { MonoValue(it) } }
      preview.phaseId?.let { PropertyRow("Phase") { MonoValue(it) } }
    }
  }
}

@Composable
/** `.sb-prop > dd .mono`: 11.5 px. */
private fun MonoValue(text: String) =
  BasicText(text, style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fg2, fontSize = SkillBillTheme.type.tXs.fontSize))
