package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbInput

private const val SIMILAR_PATTERN_HINT =
  "Commands: * matches any text. Paths are relative to the checkout: * stays in one folder, ** crosses folders."

@Composable
internal fun ApprovalSimilarEditor(
  text: String,
  onTextChange: (String) -> Unit,
  enabled: Boolean,
  refusal: String?,
  onSave: () -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    SbInput(
      value = text,
      onValueChange = onTextChange,
      modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Pattern" },
      enabled = enabled,
      mono = true,
      onSubmit = { if (enabled && text.trim().isNotBlank()) onSave() },
    )
    BasicText(SIMILAR_PATTERN_HINT, style = type.tSm.copy(color = colors.fgMuted))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      SbButton(
        label = "Save rule",
        onClick = onSave,
        size = SbControlSize.Small,
        enabled = enabled && text.trim().isNotBlank(),
      )
      SbButton(label = "Cancel", onClick = onCancel, variant = SbButtonVariant.Ghost, size = SbControlSize.Small, enabled = enabled)
    }
    refusal?.let {
      BasicText(
        it,
        style = type.tSm.copy(color = colors.stFailed),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
      )
    }
  }
}
