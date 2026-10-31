package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.domain.model.ReviewFindingStatus
import dev.skillbill.desktop.core.domain.model.ReviewReportFinding

// The review finding table shared by a standalone review, a goal subtask's review passes and a standalone run's phase results.

internal fun ReviewFindingStatus.label(): String = when (this) {
  ReviewFindingStatus.Open -> "Open"
  ReviewFindingStatus.Fixed -> "Fixed"
  ReviewFindingStatus.Accepted -> "Accepted"
  ReviewFindingStatus.Declined -> "Declined"
  ReviewFindingStatus.FalsePositive -> "False positive"
  ReviewFindingStatus.Rejected -> "Rejected"
}

/** Amber while it still asks for work, green once fixed, blue when accepted, faint once dismissed. */
@Composable
internal fun ReviewFindingStatus.color(): Color {
  val colors = SkillBillTheme.colors
  return when (this) {
    ReviewFindingStatus.Open -> colors.stBlocked
    ReviewFindingStatus.Fixed -> colors.stDone
    ReviewFindingStatus.Accepted -> colors.stActive
    ReviewFindingStatus.Declined, ReviewFindingStatus.FalsePositive, ReviewFindingStatus.Rejected -> colors.fgFaint
  }
}

/** "8 findings · 6 fixed · 2 open": the count, then each status present, in [ReviewFindingStatus] order. */
internal fun reviewFindingsTally(findings: List<ReviewReportFinding>): String {
  val count = when (findings.size) {
    0 -> "no findings"
    1 -> "1 finding"
    else -> "${findings.size} findings"
  }
  val byStatus = findings.groupingBy { it.status }.eachCount()
  val statuses = ReviewFindingStatus.entries.mapNotNull { status -> byStatus[status]?.let { "$it ${status.label().lowercase()}" } }
  return (listOf(count) + statuses).joinToString(" · ")
}

/** One finding as a row of the boxed findings table: severity, ref, text, location with lane and confidence, and its status. */
@Composable
internal fun ReviewFindingRow(index: Int, count: Int, finding: ReviewReportFinding) {
  BoxedFindingRow(
    index, count,
    title = finding.description,
    severity = finding.severity,
    ref = finding.ref.takeIf { it.isNotBlank() },
    location = finding.location,
    meta = listOfNotNull(finding.specialist, finding.confidence?.let { "$it confidence" }).joinToString(" · ").ifBlank { null },
    state = finding.status.label(),
    stateColor = finding.status.color(),
  )
}

/** The whole findings table outside a lazy list. */
@Composable
internal fun ReviewFindingsBox(findings: List<ReviewReportFinding>) {
  Column { findings.forEachIndexed { index, finding -> ReviewFindingRow(index, findings.size, finding) } }
}

/**
 * The lanes the review applied, one chip each: a green dot for a lane that came back clean, an amber dot and its finding count
 * for one that raised findings. The tooltip carries the full rubric name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReviewLanes(lanes: List<String>, findings: List<ReviewReportFinding>) {
  val colors = SkillBillTheme.colors
  val counts = remember(findings) { findings.groupingBy { it.specialist }.eachCount() }
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
    itemVerticalAlignment = Alignment.CenterVertically,
  ) {
    BasicText("Lanes", style = SkillBillTheme.type.tSmMedium.copy(color = colors.fgMuted))
    lanes.forEach { lane ->
      val count = counts[lane] ?: 0
      val label = laneChipLabel(lane)
      SbLabel(
        if (count > 0) "$label · $count" else label,
        SbLabelStyle.Dot(if (count > 0) colors.stBlocked else colors.stDone),
        tooltip = if (count > 0) "$lane · $count ${if (count == 1) "finding" else "findings"}" else "$lane · clean",
      )
    }
  }
}
