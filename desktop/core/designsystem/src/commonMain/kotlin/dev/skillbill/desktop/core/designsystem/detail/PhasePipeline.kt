package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon

private val stepHeight = 20.dp
private val connectorWidth = 6.dp
private val goalGap = 10.dp
private val checkSize = 10.dp
private const val checkStrokeWidth = 2.5f

enum class PipelineStepState { Pending, Done, Current, Waiting, Failed }

/** [goal] draws the goal-wide outline; [goalStart] marks the first goal-wide step, which gets a gap instead of a connector. */
@Immutable
class PipelineStep(
  val label: String,
  val state: PipelineStepState,
  val count: String? = null,
  val goal: Boolean = false,
  val goalStart: Boolean = false,
  val tooltip: String? = null,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhasePipeline(steps: List<PipelineStep>, modifier: Modifier = Modifier) {
  val connector = SkillBillTheme.colors.borderStrong
  FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
    steps.forEachIndexed { index, step ->
      if (index > 0) {
        if (step.goalStart) Spacer(Modifier.width(goalGap)) else Box(Modifier.size(connectorWidth, 1.dp).background(connector))
      }
      if (step.tooltip != null) SbTooltip(step.tooltip) { PipelineStepChip(step) } else PipelineStepChip(step)
    }
  }
}

@Composable
private fun PipelineStepChip(step: PipelineStep) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.xs)
  val (fg, bg) = when (step.state) {
    PipelineStepState.Pending -> colors.fgFaint to colors.bgHover
    PipelineStepState.Done -> colors.fgMuted to colors.bgHover
    PipelineStepState.Current -> colors.stActive to colors.stActive.copy(alpha = 0.12f)
    PipelineStepState.Waiting -> colors.stBlocked to colors.stBlocked.copy(alpha = 0.10f)
    PipelineStepState.Failed -> colors.stFailed to colors.stFailed.copy(alpha = 0.10f)
  }
  // `.sb-step.goal` is a transparent chip with an inset 1px `--border` ring; `.now` fills with `--st-active` and rings it at 35%, goal or not.
  val current = step.state == PipelineStepState.Current
  val outline = when {
    current -> Modifier.border(1.dp, colors.stActive.copy(alpha = 0.35f), shape)
    step.goal -> Modifier.border(1.dp, colors.border, shape)
    else -> Modifier
  }
  val text = if (step.count != null) "${step.label} · ${step.count}" else step.label
  Row(
    Modifier.height(stepHeight).background(if (step.goal && !current) Color.Transparent else bg, shape).then(outline)
      .clearAndSetSemantics { contentDescription = listOfNotNull(step.label, step.count, step.state.name.lowercase()).joinToString(", ") }
      .padding(horizontal = 7.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (step.state == PipelineStepState.Done) {
      CompositionLocalProvider(LocalContentColor provides colors.stDone) { SbIcon(LucideIcons.check, checkSize, checkStrokeWidth) }
    }
    BasicText(text, style = SkillBillTheme.type.t2xs.copy(fontFamily = SkillBillTheme.type.mono.fontFamily, fontWeight = FontWeight.Medium, color = fg), maxLines = 1)
  }
}
