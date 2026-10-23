package dev.skillbill.tray.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.body
import dev.skillbill.tray.core.designsystem.green
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.mono
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.domain.model.PlanRunReport
import dev.skillbill.tray.core.domain.model.PlannedSubtask

/** The feature a standalone plan settled and each subtask it planned, in run order, with what it waits on. */
@Composable
internal fun PlanReportSection(report: PlanRunReport) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(planSummary(report), color = green, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 17.sp)
    SelectionContainer {
      Text(report.featureName, color = ink, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 17.sp)
    }
    SpecBundleFileList(specBundlePaths(report))
    report.subtasks.forEach { PlannedSubtaskRow(it) }
  }
}

internal fun planSummary(report: PlanRunReport): String =
  if (report.subtasks.size == 1) "Planned 1 subtask." else "Planned ${report.subtasks.size} subtasks."

@Composable
private fun PlannedSubtaskRow(subtask: PlannedSubtask) {
  Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
    Text(subtask.id.toString().padStart(2, '0'), Modifier.width(22.dp), color = muted, fontFamily = mono, fontSize = 11.sp, lineHeight = 17.sp)
    SelectionContainer(Modifier.weight(1f)) {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(subtask.name, color = ink, fontFamily = body, fontSize = 12.sp, lineHeight = 17.sp)
        Text(
          listOfNotNull(subtask.specPath.substringAfterLast('/'), dependsOnLabel(subtask.dependsOn)).joinToString(" · "),
          color = muted, fontFamily = mono, fontSize = 11.sp, lineHeight = 16.sp,
        )
      }
    }
  }
}

private fun dependsOnLabel(dependsOn: List<Int>): String? =
  dependsOn.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "after ") { "#$it" }
