package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons

/**
 * A no-change pause waits for an operator decision. The app does not record it; the operator settles it in chat or runs
 * [OperatorDecisionNeeded.command], then resumes the goal with the ordinary launch button.
 */
@Composable
internal fun OperatorDecisionNotice(needed: OperatorDecisionNeeded, onCopy: (String) -> Unit) {
  val type = SkillBillTheme.type
  val colors = SkillBillTheme.colors
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Hint(needed.text, FeedbackTone.Info)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      BasicText(needed.command, Modifier.weight(1f), style = type.monoSm.copy(color = colors.fg2))
      SbIconButton(LucideIcons.copy, "Copy decision command", { onCopy(needed.command) }, size = SbControlSize.Small)
    }
  }
}
