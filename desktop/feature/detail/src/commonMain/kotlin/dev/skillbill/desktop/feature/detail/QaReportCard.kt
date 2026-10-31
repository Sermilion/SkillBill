package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
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
      if (report != null) ReportText(report)
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
