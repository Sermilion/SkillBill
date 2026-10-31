package dev.skillbill.desktop.feature.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.PipelineStep
import dev.skillbill.desktop.core.designsystem.detail.PipelineStepState
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.GoalAssessmentState
import dev.skillbill.desktop.core.domain.model.GoalAssessmentVerification
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.PlanRunReport
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import kotlin.time.Instant

/** "d MMM HH:mm"-style formatting in the system time zone. */
internal expect fun formatInstant(instant: Instant, pattern: String): String

/** [path] with the user's home directory shown as `~`. */
internal expect fun abbreviateHome(path: String): String

/** The id a row shows, as the Work board shows it: a goal's issue key, else a run's phase id or issue key. */
internal fun rowKey(item: WorkItem): String =
  if (item.kind == WorkKind.Goal) item.issueKey ?: item.id else item.control.phaseId ?: item.issueKey ?: item.id

/** Goal, operation, or phase wording; a row the runtime placed in no group reads as a phase. */
internal fun kindWord(kind: WorkKind?): String = when (kind) {
  WorkKind.Goal -> "goal"
  WorkKind.Operation -> "operation"
  WorkKind.Phase, null -> "phase"
}

/** The status colors `StatusIcon` draws with. */
@Composable
internal fun statusColor(kind: StatusKind): Color {
  val colors = SkillBillTheme.colors
  return when (kind) {
    StatusKind.Active -> colors.stActive
    StatusKind.Planning -> colors.stPlanning
    StatusKind.Stalled, StatusKind.Pending, StatusKind.Skipped, StatusKind.Canceled -> colors.stPending
    StatusKind.Paused -> colors.stPaused
    StatusKind.Blocked -> colors.stBlocked
    StatusKind.Failed -> colors.stFailed
    StatusKind.Completed, StatusKind.CompletedNoChange -> colors.stDone
  }
}

/** The lifecycle chip's word, as the design's `LIFE` table names it: the raw lifecycle, not the derived status. */
internal fun lifecycleLabel(lifecycle: Lifecycle): String = when (lifecycle) {
  Lifecycle.Active -> "Active"
  Lifecycle.Paused -> "Paused"
  Lifecycle.Blocked -> "Blocked"
  Lifecycle.Failed -> "Failed"
  Lifecycle.Terminal -> "Completed"
  Lifecycle.Canceled -> "Canceled"
  Lifecycle.Idle -> "Idle"
}

/** The lifecycle color the chip and the run reason's left rule use. */
@Composable
internal fun lifecycleColor(lifecycle: Lifecycle): Color {
  val colors = SkillBillTheme.colors
  return when (lifecycle) {
    Lifecycle.Active -> colors.stActive
    Lifecycle.Paused -> colors.stPaused
    Lifecycle.Blocked -> colors.stBlocked
    Lifecycle.Failed -> colors.stFailed
    Lifecycle.Terminal -> colors.stDone
    Lifecycle.Canceled, Lifecycle.Idle -> colors.stPending
  }
}

/** When the run finished, or null while it is live; a finished run shows its total time. */
internal fun finishedAt(item: WorkItem): Instant? = item.finishedAt?.takeIf { item.lifecycle != Lifecycle.Active && item.liveSection == null }

internal fun elapsedLabel(kind: WorkKind?): String = when (kind) {
  WorkKind.Goal -> "Goal active"
  WorkKind.Operation -> "Operation elapsed"
  WorkKind.Phase, null -> "Phase elapsed"
}

/**
 * Places a run on a step pipeline: steps before the current one are done, and a finished run completes them all. The goal
 * finish pipeline ([goal]) only has a current step once no subtask is running. screens.js `pipelineSteps()` puts the execution
 * count on a goal's current phase whatever its state; a standalone run's steps carry it only on the running step.
 */
internal fun pipelineSteps(item: WorkItem, detail: Detail, pipeline: List<String>, goal: Boolean = false): List<PipelineStep> {
  val current = if (!goal || item.currentSubtaskId == null) pipeline.indexOf(detail.currentPhase) else -1
  val countOnAnyState = item.kind == WorkKind.Goal && !goal
  return pipeline.mapIndexed { index, id ->
    val state = when {
      item.lifecycle == Lifecycle.Terminal -> PipelineStepState.Done
      current < 0 -> PipelineStepState.Pending
      index < current -> PipelineStepState.Done
      index == current -> when (item.lifecycle) {
        Lifecycle.Active -> PipelineStepState.Current
        Lifecycle.Blocked -> PipelineStepState.Waiting
        Lifecycle.Failed -> PipelineStepState.Failed
        else -> PipelineStepState.Pending
      }
      else -> PipelineStepState.Pending
    }
    val counted = if (countOnAnyState) index == current && state != PipelineStepState.Done else state == PipelineStepState.Current
    PipelineStep(id, state, count = item.phaseExecutionLabel.takeIf { counted }, goal = goal)
  }
}

private val ASSESSMENT_STEPS = setOf("goal_assessment", "goal_assessment_fix")

/** The goal finish steps; a settled assessment completes its steps, and a skipped one shows them pending with a "skipped" tooltip. */
internal fun goalFinishSteps(item: WorkItem, detail: Detail): List<PipelineStep> {
  val state = detail.goalAssessment?.state
  return pipelineSteps(item, detail, detail.goalFinishPipeline, goal = true).map { step ->
    when {
      step.label !in ASSESSMENT_STEPS -> step
      state == GoalAssessmentState.Blocked -> PipelineStep(step.label, PipelineStepState.Failed, goal = true)
      state == GoalAssessmentState.Completed -> PipelineStep(step.label, PipelineStepState.Done, goal = true)
      state == GoalAssessmentState.Skipped -> PipelineStep(step.label, PipelineStepState.Pending, goal = true, tooltip = "skipped")
      else -> step
    }
  }
}

/** screens.js Goal QA: "QA found 3 findings; all fixed after 2 fix attempts." or "QA could not fix 1 finding. Review them yourself." */
internal fun qaFindingsSummary(total: Int, notFixed: Int, attempts: Int): String {
  val after = when (attempts) { 0 -> ""; 1 -> " after 1 fix attempt"; else -> " after $attempts fix attempts" }
  return if (notFixed == 0) {
    "QA found ${findingCount(total)}; all fixed$after."
  } else {
    "QA could not fix ${findingCount(notFixed)}. Review them yourself."
  }
}

private fun findingCount(count: Int): String = if (count == 1) "1 finding" else "$count findings"

internal fun goalAssessmentVerificationLabel(verification: GoalAssessmentVerification): String = when (verification) {
  GoalAssessmentVerification.Confirmed -> "confirmed"
  GoalAssessmentVerification.Refuted -> "refuted"
  GoalAssessmentVerification.Unverified -> "unverified"
  GoalAssessmentVerification.NotRequired -> "verification not required"
}

/** The runtime-supplied bundle files, or the parent and subtask spec paths when an older runtime sent none. Paths are never rewritten. */
internal fun specBundlePaths(report: PlanRunReport): List<String> =
  report.bundleFiles.ifEmpty { listOf(report.parentSpecPath) + report.subtasks.map { it.specPath } }
    .filter { it.isNotBlank() }
    .distinct()
    .sortedWith(compareBy<String> { specFileRank(fileName(it)) }.then { a, b -> naturalCompare(fileName(a), fileName(b)) })

/** The decomposition manifest first, then spec.md, then every other file by name. */
private fun specFileRank(name: String): Int = when {
  name.startsWith("decomposition") -> 0
  name == "spec.md" -> 1
  else -> 2
}

/** Name order that reads digit runs as numbers, so spec_subtask_2 sorts before spec_subtask_10. */
internal fun naturalCompare(a: String, b: String): Int {
  val chunk = Regex("\\d+|\\D+")
  val left = chunk.findAll(a.lowercase()).map { it.value }.toList()
  val right = chunk.findAll(b.lowercase()).map { it.value }.toList()
  for (i in 0 until minOf(left.size, right.size)) {
    val x = left[i]
    val y = right[i]
    val result = if (x[0].isDigit() && y[0].isDigit()) {
      compareValuesBy(x, y, { it.trimStart('0').length }, { it.trimStart('0') })
    } else {
      x.compareTo(y)
    }
    if (result != 0) return result
  }
  return left.size.compareTo(right.size)
}

internal fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')
