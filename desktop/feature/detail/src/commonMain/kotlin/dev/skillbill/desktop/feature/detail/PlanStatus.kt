package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.designsystem.component.SbSpinner
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.detail.DocBlockGap
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.StandalonePlanProgress
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlin.time.Instant

@Composable
internal fun PlanStageStrip(item: WorkItem, detail: Detail, onStageClick: (String) -> Unit) {
  val views = planStageViews(item, detail)
  val hasRunningStage = item.running && views.any { it.state == PlanStageState.Running }
  val ticker = LocalUiTicker.current
  if (hasRunningStage) DisposableEffect(ticker) { ticker.retain(); onDispose { ticker.release() } }
  val now = if (hasRunningStage) ticker.now.value else null
  val separatorColor = SkillBillTheme.colors.borderSubtle
  val shape = RoundedCornerShape(SkillBillRadius.md)
  Row(
    Modifier.fillMaxWidth().padding(top = DocBlockGap).clip(shape).border(1.dp, SkillBillTheme.colors.border, shape),
  ) {
    views.forEachIndexed { index, view ->
      val interaction = remember(view.phaseId) { MutableInteractionSource() }
      val quiet = view.state == PlanStageState.Running && planQuietSince(item, detail) != null
      val blocked = view.state == PlanStageState.Blocked
      val active = view.state == PlanStageState.Running
      val tint = when {
        blocked || quiet || view.state == PlanStageState.Failed -> SkillBillTheme.colors.stBlocked
        active -> SkillBillTheme.colors.stActive
        view.state == PlanStageState.Done -> SkillBillTheme.colors.fg2
        view.state == PlanStageState.Paused -> SkillBillTheme.colors.fgMuted
        else -> SkillBillTheme.colors.fgFaint
      }
      val background = when {
        blocked || quiet -> SkillBillTheme.colors.stBlocked.copy(alpha = .07f)
        active -> SkillBillTheme.colors.stActive.copy(alpha = .06f)
        else -> Color.Transparent
      }
      val elapsed = when {
        view.state == PlanStageState.NotStarted -> null
        view.startedAt == null -> null
        active -> now?.let { elapsedText(view.startedAt, it) }
        else -> elapsedText(view.startedAt, view.finishedAt ?: detail.updatedAt)
      }
      Column(
        Modifier.weight(1f)
          .testTag("plan-stage-${view.phaseId}")
          .semantics {
            stateDescription = when {
              quiet -> "Running, waiting for output"
              view.state == PlanStageState.NotStarted -> "Not started"
              view.state == PlanStageState.Done -> "Completed"
              else -> view.state.name
            }
          }
          .background(background)
          .then(if (index == 0) Modifier else Modifier.drawBehind {
            drawLine(
              separatorColor,
              Offset(0f, 0f),
              Offset(0f, size.height),
              strokeWidth = 1.dp.toPx(),
            )
          })
          .then(if (view.state == PlanStageState.NotStarted) Modifier else Modifier
            .sbFocusRing(interaction, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onStageClick(view.phaseId) })
          .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          when {
            active -> SbSpinner(
              11.dp,
              tint,
              modifier = Modifier.rotate(-90f),
              durationMillis = if (quiet) 2_400 else 900,
              trackAlpha = .25f,
              arcSweep = 90f,
            )
            blocked || view.state == PlanStageState.Failed -> StageIcon(LucideIcons.warn, tint)
            view.state == PlanStageState.Done -> StageIcon(LucideIcons.check, SkillBillTheme.colors.stDone)
            view.state == PlanStageState.NotStarted -> DashedRing(tint)
            else -> StageIcon(LucideIcons.check, SkillBillTheme.colors.fgMuted)
          }
          BasicText(
            view.label,
            Modifier.weight(1f),
            style = SkillBillTheme.type.tSm.copy(color = tint, fontWeight = FontWeight.Medium),
            maxLines = 1,
          )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          BasicText(view.phaseId, style = SkillBillTheme.type.t2xs.copy(color = SkillBillTheme.colors.fgFaint))
          Spacer(Modifier.weight(1f))
          if (elapsed != null) {
            BasicText(
              elapsed,
              style = SkillBillTheme.type.tXs.copy(color = if (active) SkillBillTheme.colors.fg else SkillBillTheme.colors.fgMuted),
              maxLines = 1,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun StageIcon(icon: ImageVector, color: Color) {
  CompositionLocalProvider(LocalContentColor provides color) { SbIcon(icon, 11.dp) }
}

@Composable
private fun DashedRing(color: Color) {
  Canvas(Modifier.size(11.dp)) {
    drawCircle(color, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))))
  }
}

internal fun planStatusText(item: WorkItem, detail: Detail): String {
  val plan = detail.standalonePlan
  return when {
    hasBlockedSubtasks(plan) -> "${blockedSubtasksLabel(blockedSubtaskIds(plan))}. ${plan?.plannedSubtaskCount} of ${plan?.totalSubtaskCount} saved, ${plan?.units?.count { it.state == "queued" }} not started."
    planFinished(item, detail) -> "Plan ready. ${plan?.totalSubtaskCount ?: 0} subtasks, all specs saved."
    !item.running -> lifecycleLabel(item.lifecycle)
    else -> {
      val views = planStageViews(item, detail).associateBy { it.phaseId }
      when {
        !plan?.units.isNullOrEmpty() || views["plan_subtasks"]?.state == PlanStageState.Running -> {
          val writing = plan?.units.orEmpty().filter { it.state == "writing" }.map { it.subtaskId }
          val action = when {
            writing.isEmpty() -> "Writing specs"
            writing.size == 1 -> "Writing specs for subtask ${writing.single()}"
            else -> "Writing specs for subtasks ${writing.min()} to ${writing.max()}"
          }
          "$action. ${plan?.plannedSubtaskCount ?: 0} of ${plan?.totalSubtaskCount ?: 0} saved."
        }
        views["plan"]?.state == PlanStageState.Running -> "Splitting the work into subtasks. Their specs appear in the bundle once the manifest is saved."
        else -> "Reading the repository and the intake before it splits the work."
      }
    }
  }
}

internal fun hasBlockedSubtasks(plan: StandalonePlanProgress?): Boolean = blockedSubtaskIds(plan).isNotEmpty()

internal fun blockedSubtaskIds(plan: StandalonePlanProgress?): List<Int> =
  plan?.blockedSubtaskIds.orEmpty().ifEmpty { plan?.blockedSubtaskId?.let(::listOf).orEmpty() }

internal fun blockedSubtasksLabel(ids: List<Int>): String =
  "Planning stopped at ${if (ids.size == 1) "subtask" else "subtasks"} ${ids.joinToString(", ")}"

internal const val PLAN_QUIET_SECONDS = 5 * 60L

internal fun planQuietSince(item: WorkItem, detail: Detail): Instant? {
  if (!item.running) return null
  val latest = detail.planOutputUpdatedAt ?: item.startedAt ?: return null
  return latest.takeIf { (detail.observedAt - it).inWholeSeconds >= PLAN_QUIET_SECONDS }
}
