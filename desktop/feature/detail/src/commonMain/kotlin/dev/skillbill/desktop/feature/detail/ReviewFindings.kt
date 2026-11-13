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

/** `bill-kotlin-code-review-testing` reads `testing`; a name without the marker stays whole. */
internal fun laneAreaLabel(lane: String): String = lane.substringAfter("-code-review-", lane)

internal fun ReviewFindingStatus.label(): String = when (this) {
  ReviewFindingStatus.Open -> "Open"
  ReviewFindingStatus.Fixed -> "Fixed"
  ReviewFindingStatus.Accepted -> "Accepted"
  ReviewFindingStatus.Declined -> "Declined"
  ReviewFindingStatus.FalsePositive -> "False positive"
  ReviewFindingStatus.Rejected -> "Refuted"
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

private val TALLY_ORDER = listOf(
  ReviewFindingStatus.Fixed,
  ReviewFindingStatus.Open,
  ReviewFindingStatus.Accepted,
  ReviewFindingStatus.Declined,
  ReviewFindingStatus.FalsePositive,
  ReviewFindingStatus.Rejected,
)

/** "8 findings · 6 fixed · 2 open": the count, then each status present, in [TALLY_ORDER]. */
internal fun reviewFindingsTally(findings: List<ReviewReportFinding>): String {
  val count = when (findings.size) {
    0 -> "no findings"
    1 -> "1 finding"
    else -> "${findings.size} findings"
  }
  val byStatus = findings.groupingBy { it.status }.eachCount()
  val statuses = TALLY_ORDER.mapNotNull { status -> byStatus[status]?.let { "$it ${status.label().lowercase()}" } }
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
    meta = listOfNotNull(
      finding.specialist?.takeIf { it.isNotBlank() }?.let(::laneAreaLabel),
      finding.confidence?.takeIf { it.isNotBlank() }?.let { "$it confidence" },
    ).joinToString(" · ").ifBlank { null },
    state = finding.status.label(),
    stateColor = finding.status.color(),
  )
}

/** The whole findings table outside a lazy list. */
@Composable
internal fun ReviewFindingsBox(findings: List<ReviewReportFinding>) {
  Column { findings.forEachIndexed { index, finding -> ReviewFindingRow(index, findings.size, finding) } }
}

/** Empty pass or every finding has a specialist — enough attribution to claim per-lane zero. */
internal fun reviewFindingsAttributionComplete(findings: List<ReviewReportFinding>): Boolean =
  findings.isEmpty() || findings.all { !it.specialist.isNullOrBlank() }

internal fun reviewAttributedFindingsPerLane(findings: List<ReviewReportFinding>): Map<String, Int> =
  findings
    .mapNotNull { it.specialist?.takeIf(String::isNotBlank) }
    .groupingBy { it }
    .eachCount()

/**
 * Recorded review lanes, one chip each: amber and a count when attributed findings target the lane; green only when
 * attribution is complete and that lane has zero attributed findings; otherwise a neutral dot (no false clean).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReviewLanes(lanes: List<String>, findings: List<ReviewReportFinding>) {
  val colors = SkillBillTheme.colors
  val attributionComplete = remember(findings) { reviewFindingsAttributionComplete(findings) }
  val counts = remember(findings) { reviewAttributedFindingsPerLane(findings) }
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
    itemVerticalAlignment = Alignment.CenterVertically,
  ) {
    BasicText("Lanes", style = SkillBillTheme.type.tSmMedium.copy(color = colors.fgMuted))
    lanes.forEach { lane ->
      val count = counts[lane] ?: 0
      val label = laneChipLabel(lane)
      when {
        count > 0 -> SbLabel(
          "$label · $count",
          SbLabelStyle.Dot(colors.stBlocked),
          tooltip = "$lane · $count ${if (count == 1) "finding" else "findings"}",
        )
        attributionComplete -> SbLabel(
          label,
          SbLabelStyle.Dot(colors.stDone),
          tooltip = "$lane · clean",
        )
        else -> SbLabel(label, SbLabelStyle.Dot(null), tooltip = lane)
      }
    }
  }
}
