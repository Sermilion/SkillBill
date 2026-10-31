package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons

@Composable
internal fun ChatToolRow(tool: ChatToolEntry) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  var expanded by rememberSaveable(tool.call.id) { mutableStateOf(false) }
  val summary = tool.call.summary?.lineSequence()?.firstOrNull { it.isNotBlank() }
  val label = listOfNotNull(tool.call.name, summary).joinToString(": ")
  Column(
    Modifier.fillMaxWidth().background(colors.bgRaised, RoundedCornerShape(SkillBillRadius.sm)).padding(horizontal = 10.dp, vertical = 4.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      BasicText(label, Modifier.weight(1f), style = type.monoSm.copy(color = colors.fgMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
      SbButton(
        if (expanded) "Show less" else "Show more",
        { expanded = !expanded },
        modifier = Modifier.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
        variant = SbButtonVariant.Ghost,
        size = SbControlSize.Small,
        icon = if (expanded) LucideIcons.chevronUp else LucideIcons.chevronDown,
      )
    }
    if (expanded) {
      SelectionContainer {
        Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          BasicText("Input", style = type.tSmMedium.copy(color = colors.fgMuted))
          BasicText(tool.call.input.ifBlank { "No input provided." }, style = type.monoSm.copy(color = colors.fg))
          val result = tool.result
          BasicText(
            when {
              result == null -> "Result not received yet."
              result.isError -> "Failed"
              else -> "Completed"
            },
            style = type.tSmMedium.copy(color = if (result?.isError == true) colors.stFailed else colors.fgMuted),
          )
          if (result != null) BasicText(result.output.ifBlank { "No output." }, style = type.monoSm.copy(color = colors.fg))
        }
      }
    }
  }
}
