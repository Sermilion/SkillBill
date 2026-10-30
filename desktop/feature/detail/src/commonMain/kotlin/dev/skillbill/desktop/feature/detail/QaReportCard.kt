package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.ExpandableText
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.domain.model.GoalQaResult

@Composable
internal fun QaReportCard(qa: GoalQaResult) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  var detailsOpen by remember(qa.recordedAt) { mutableStateOf(false) }
  SbBox {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      qa.failureKind?.let { BasicText(it.replace('_', ' ').replaceFirstChar(Char::uppercase), style = type.tSmMedium.copy(color = colors.fgMuted)) }
      val report = qa.reportText?.takeIf { it.isNotBlank() }
      if (report != null) {
        SelectionContainer {
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val blocks = remember(report) { report.trim().split(Regex("\\n\\s*\\n")) }
            blocks.forEach { block ->
              val lines = block.lines()
              when {
                block.startsWith("#") -> BasicText(block.trimStart('#', ' '), style = type.tBaseMedium.copy(color = colors.fg))
                lines.all { it.startsWith("- ") || it.startsWith("* ") } -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  lines.forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                      BasicText("•", style = type.tSm.copy(color = colors.fgFaint))
                      BasicText(line.drop(2), style = type.tSm.copy(color = colors.fg2))
                    }
                  }
                }
                block.startsWith("```") -> BasicText(lines.drop(1).dropLastWhile { it.startsWith("```") }.joinToString("\n"), style = type.monoSm.copy(color = colors.fg2))
                else -> BasicText(lines.joinToString(" "), style = type.tSm.copy(color = colors.fg2))
              }
            }
          }
        }
      }
      qa.reason?.takeIf { it.isNotBlank() }?.let { reason ->
        if (report == null) {
          ExpandableText(reason, type.tSm.copy(color = colors.fgMuted))
        } else {
          SbButton(if (detailsOpen) "Hide run details" else "Show run details", { detailsOpen = !detailsOpen }, variant = SbButtonVariant.Ghost)
          if (detailsOpen) ExpandableText(reason, type.tSm.copy(color = colors.fgFaint, fontWeight = FontWeight.Normal))
        }
      }
    }
  }
}
