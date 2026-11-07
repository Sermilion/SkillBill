package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.component.SbTag
import dev.skillbill.desktop.core.designsystem.detail.PaneSection
import dev.skillbill.desktop.core.designsystem.detail.sbBoxSegment
import dev.skillbill.desktop.core.designsystem.status.StatusDot
import dev.skillbill.desktop.core.domain.model.ReviewFindingStatus
import dev.skillbill.desktop.core.domain.model.ReviewPass
import dev.skillbill.desktop.core.domain.model.ReviewReportFinding
import dev.skillbill.desktop.core.domain.model.RunningReviewPass

/** The Review caption with its pass count or running pass, then one block per completed pass. */
@Composable
internal fun SubtaskReviewSection(
  passes: List<ReviewPass>,
  runningReviewPass: RunningReviewPass? = null,
) {
  val count = if (passes.size == 1) "1 pass" else "${passes.size} passes"
  PaneSection(
    title = "Review",
    end = {
      BasicText(
        runningReviewPass?.let { "pass ${it.passNumber} running" } ?: count,
        style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted),
      )
    },
  ) {
    passes.forEachIndexed { index, pass ->
      PassBlock(
        pass,
        Modifier.padding(top = if (index > 0) 14.dp else 0.dp),
        showDetails = runningReviewPass == null,
      )
    }
    runningReviewPass?.let {
      RunningReviewPassBlock(it, Modifier.padding(top = if (passes.isNotEmpty()) 14.dp else 0.dp))
    }
  }
}

/** A pass: its verdict and status tally, the lanes its review run applied, then its findings. */
@Composable
private fun PassBlock(pass: ReviewPass, modifier: Modifier, showDetails: Boolean = true) {
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    InspectorLine(
      "Pass ${pass.passNumber}",
      listOf(reviewVerdictLabel(pass.verdict), reviewFindingsTally(pass.findings)),
    )
    if (showDetails && pass.lanes.isNotEmpty()) ReviewLanes(pass.lanes, pass.findings)
    if (showDetails && pass.findings.isNotEmpty()) InspectorFindingsBox(pass.findings)
  }
}

@Composable
private fun RunningReviewPassBlock(pass: RunningReviewPass, modifier: Modifier) {
  val finishedCount = pass.lanes.count { it.finished }
  val details =
    if (pass.lanes.isEmpty()) {
      listOf("Reviewing")
    } else {
      listOf("Reviewing", "$finishedCount of ${pass.lanes.size} lanes done")
    }
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      StatusDot(SkillBillTheme.colors.stActive)
      Box(Modifier.weight(1f)) { InspectorLine("Pass ${pass.passNumber}", details) }
    }
    if (pass.lanes.isNotEmpty()) RunningReviewLanes(pass)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunningReviewLanes(pass: RunningReviewPass) {
  val colors = SkillBillTheme.colors
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
    itemVerticalAlignment = Alignment.CenterVertically,
  ) {
    BasicText("Lanes", style = SkillBillTheme.type.tSmMedium.copy(color = colors.fgMuted))
    pass.lanes.forEach { lane ->
      val label = laneChipLabel(lane.name)
      Box(
        Modifier.semantics(mergeDescendants = true) {
          stateDescription = if (lane.finished) "Finished" else "Not finished"
        },
      ) {
        SbLabel(
          text = label,
          style = SbLabelStyle.Dot(if (lane.finished) colors.stDone else null),
          tooltip = "${lane.name} · ${if (lane.finished) "finished" else "not finished"}",
          dashed = !lane.finished,
        )
      }
    }
  }
}

@Composable
private fun InspectorFindingsBox(findings: List<ReviewReportFinding>) {
  Column(Modifier.fillMaxWidth().background(SkillBillTheme.colors.bgInset, RoundedCornerShape(SkillBillRadius.md))) {
    findings.forEachIndexed { index, finding ->
      InspectorFindingRow(index, finding, Modifier.fillMaxWidth().sbBoxSegment(index, findings.size))
    }
  }
}

/** A finding in the narrow inspector: badge, text and state on one line, its id, file and lane on the next. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InspectorFindingRow(index: Int, finding: ReviewReportFinding, modifier: Modifier) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val divider = colors.borderSubtle
  val dismissed = finding.status == ReviewFindingStatus.Declined ||
    finding.status == ReviewFindingStatus.FalsePositive ||
    finding.status == ReviewFindingStatus.Rejected
  Column(
    modifier.drawBehind {
      if (index > 0) {
        val stroke = 1.dp.toPx()
        drawLine(divider, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
      }
    }.padding(horizontal = 12.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
      SbTag(finding.severity, tone = when (finding.severity.lowercase()) {
        "blocker" -> colors.stFailed
        "major" -> colors.stBlocked
        else -> null
      })
      BasicText(
        finding.description,
        Modifier.weight(1f),
        style = type.tBase.copy(color = if (dismissed) colors.fgMuted else colors.fg, lineHeight = 1.45.em),
      )
      FindingState(finding.status)
    }
    val meta = findingMetaLabel(finding.specialist, finding.confidence)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      finding.ref.takeIf { it.isNotBlank() }?.let { BasicText(it, style = type.monoSm.copy(color = colors.fgMuted)) }
      finding.location?.takeIf { it.isNotBlank() }?.let {
        BasicText(it.substringAfterLast('/'), style = type.monoSm.copy(color = colors.fgMuted))
      }
      if (meta.isNotEmpty()) BasicText(meta, style = type.tSm.copy(color = colors.fgMuted))
    }
  }
}

@Composable
private fun FindingState(status: ReviewFindingStatus) {
  val color = status.color()
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    StatusDot(color)
    BasicText(status.label(), style = SkillBillTheme.type.tSmMedium.copy(color = color), maxLines = 1, softWrap = false)
  }
}

/** `bill-kotlin-code-review-testing` reads `testing`; a name without the marker stays whole. */
private fun laneAreaLabel(lane: String): String = lane.substringAfter("-code-review-", lane)

/** "<area> · <confidence> confidence", with missing parts omitted. */
private fun findingMetaLabel(specialist: String?, confidence: String?): String = listOfNotNull(
  specialist?.takeIf { it.isNotBlank() }?.let(::laneAreaLabel),
  confidence?.takeIf { it.isNotBlank() }?.let { "$it confidence" },
).joinToString(" · ")
