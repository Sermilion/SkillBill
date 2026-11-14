package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.detail.PeekPart
import dev.skillbill.desktop.core.designsystem.status.StatusDot
import dev.skillbill.desktop.core.domain.model.ReviewPass
import dev.skillbill.desktop.core.domain.model.RunningReviewPass

/** The Review caption with its pass count or running pass, then one block per completed pass. */
@Composable
internal fun SubtaskReviewSection(
  passes: List<ReviewPass>,
  runningReviewPass: RunningReviewPass? = null,
) {
  val count = if (passes.size == 1) "1 pass" else "${passes.size} passes"
  PeekPart("Findings", end = runningReviewPass?.let { "pass ${it.passNumber} running" } ?: count) {
    passes.forEachIndexed { index, pass ->
      PassBlock(pass, Modifier.padding(top = if (index > 0) 14.dp else 0.dp))
    }
    runningReviewPass?.let {
      RunningReviewPassBlock(it, Modifier.padding(top = if (passes.isNotEmpty()) 14.dp else 0.dp))
    }
  }
}

/** A pass: its verdict and status tally, the lanes its review run applied, then its findings. */
@Composable
private fun PassBlock(pass: ReviewPass, modifier: Modifier) {
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    InspectorLine(
      "Pass ${pass.passNumber}",
      listOf(reviewVerdictLabel(pass.verdict), reviewFindingsTally(pass.findings)),
    )
    if (pass.lanes.isNotEmpty()) ReviewLanes(pass.lanes, pass.findings)
    if (pass.findings.isNotEmpty()) ReviewFindingsBox(pass.findings)
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
