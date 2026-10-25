package dev.skillbill.tray.feature.detail

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.ExecutionInstance
import dev.skillbill.tray.core.domain.model.ExecutionScope
import dev.skillbill.tray.core.domain.model.GoalAssessmentResult
import dev.skillbill.tray.core.domain.model.GoalAssessmentState
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.ReasonRestoration
import dev.skillbill.tray.core.domain.model.RunOutcome
import dev.skillbill.tray.core.domain.model.WorkFamily
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.WorkQaOutcome
import dev.skillbill.tray.core.domain.model.outcome
import dev.skillbill.tray.core.domain.model.standaloneExecutionOutcome
import dev.skillbill.tray.core.ui.age
import dev.skillbill.tray.core.ui.durationText
import dev.skillbill.tray.core.ui.formatInstant
import dev.skillbill.tray.core.ui.phaseElapsedText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The outcome of one run in execution history. The run the item describes uses the item's outcome; an older run
 * reads its own stored lifecycle state and QA verdict.
 */
internal fun executionOutcome(item: WorkItem, instance: ExecutionInstance): RunOutcome =
  if (instance.executionId == item.executionId) {
    item.outcome()
  } else {
    standaloneExecutionOutcome(instance.lifecycleState, instance.qaVerdict)
  }

internal fun qaFailureKindLabel(failureKind: String?): String? = when (failureKind) {
  "environment" -> "environment error"
  "defect" -> "runtime defect"
  else -> null
}

/** The recorded QA of a non-goal workflow (a standalone feature run); a goal shows its own QA result instead. */
internal fun workflowQaOutcome(item: WorkItem): WorkQaOutcome? =
  item.qaOutcome?.takeIf { item.family != WorkFamily.FEATURE_GOAL }

internal data class DetailDuration(val label: String, val value: String, val note: String)

internal fun detailDuration(item: WorkItem, now: Instant, stale: Boolean, frozen: Long? = null): DetailDuration {
  val finished = item.finishedAt?.takeIf { item.lifecycle != Lifecycle.ACTIVE && item.liveSection == null }
  if (finished != null) {
    val value = item.activeDurationMs?.let { age(it.milliseconds) } ?: "not available"
    return DetailDuration("Total run time", value, "finished ${formatInstant(finished, "d MMM HH:mm")}")
  }
  val operation = item.family == WorkFamily.FEATURE_VERIFY
  val standalone = item.scope == ExecutionScope.STANDALONE_PHASE
  val value = if ((standalone && !item.goalLaunched) || operation) phaseElapsedText(item, now, stale) else
    durationText(item.activeDurationMs, item.activeDurationAsOf, item, now, stale, frozen)
  return DetailDuration(
    if (operation) "Operation elapsed" else if (standalone && !item.goalLaunched) "Phase elapsed" else "Goal active",
    value,
    item.startedAt?.let { "since ${formatInstant(it, "d MMM HH:mm")}" } ?: "",
  )
}

private val KEBAB_TITLE = Regex("[a-z0-9]+(?:-[a-z0-9]+)+")

internal fun detailTitle(detail: Detail): String {
  if (detail.item.family == WorkFamily.FEATURE_VERIFY) return "Operation: Verify"
  detail.featureTitle?.takeIf { it.isNotBlank() }?.let { return displayFeatureTitle(it) }
  val item = detail.item
  val title = if (item.goalLaunched) "Goal" else if (item.scope == ExecutionScope.STANDALONE_PHASE) {
    item.phaseId?.takeIf { it.isNotBlank() }?.replace('_', ' ')?.replace('-', ' ')?.replaceFirstChar(Char::uppercase) ?: "Standalone phase"
  } else when (item.family) {
    WorkFamily.FEATURE_GOAL -> "Goal"
    else -> "Feature work"
  }
  return item.issueKey?.let { "$title · $it" } ?: title
}

private fun displayFeatureTitle(value: String): String =
  if (KEBAB_TITLE.matches(value)) value.replace('-', ' ').replaceFirstChar(Char::uppercase) else value

/** Says so when a legacy reason could not be recovered in full; an exact or restored reason needs no note. */
internal fun restorationNote(restoration: ReasonRestoration?): String? = when (restoration) {
  null, ReasonRestoration.EXACT, ReasonRestoration.RESTORED -> null
  ReasonRestoration.MISSING, ReasonRestoration.MISSING_CONTENT -> "The full original reason is unavailable; this text may be shortened."
  ReasonRestoration.AMBIGUOUS -> "Several records matched this older reason, so the full text could not be restored; this text may be shortened."
  ReasonRestoration.BUDGET_EXCEEDED -> "The full original reason was not restored within the read budget; this text may be shortened."
}

/** The spec bundle a standalone plan run has written so far, empty until its plan report arrives. */
internal fun standalonePlanNote(detail: Detail): String =
  detail.executionHistory.firstOrNull { it.invocationId == detail.item.invocationId }?.planReport
    ?.subtasks?.size?.let { count -> if (count == 1) "1 subtask spec" else "$count subtask specs" }
    .orEmpty()

internal enum class PipelineStepState { PENDING, ACTIVE, COMPLETE, SKIPPED }

internal data class PipelineStep(val id: String, val state: PipelineStepState)

/** Places a run on a step pipeline: steps before the current one are done, and a finished run completes them all. */
internal fun pipelineSteps(item: WorkItem, pipeline: List<String>): List<PipelineStep> {
  val current = if (item.currentSubtaskId == null) pipeline.indexOf(item.currentStepId) else -1
  return pipeline.mapIndexed { index, id ->
    val state = when {
      item.lifecycle == Lifecycle.TERMINAL -> PipelineStepState.COMPLETE
      current < 0 -> PipelineStepState.PENDING
      index < current -> PipelineStepState.COMPLETE
      index == current && item.lifecycle == Lifecycle.ACTIVE -> PipelineStepState.ACTIVE
      else -> PipelineStepState.PENDING
    }
    PipelineStep(id, state)
  }
}

private const val ASSESSMENT_STEP = "goal_assessment"
private const val ASSESSMENT_FIX_STEP = "goal_assessment_fix"

/** The goal finish steps, where the assessment pills follow the assessment record: a skipped stage stays skipped. */
internal fun goalFinishSteps(item: WorkItem, detail: Detail): List<PipelineStep> {
  val assessment = detail.goalAssessment
  return pipelineSteps(item, detail.goalFinishPipeline).map { step ->
    when {
      assessment == null -> step
      step.id == ASSESSMENT_STEP -> step.copy(state = assessment.stepState(item.lifecycle))
      step.id == ASSESSMENT_FIX_STEP -> step.copy(state = assessment.fixStepState(item.lifecycle))
      else -> step
    }
  }
}

private fun GoalAssessmentResult.stepState(lifecycle: Lifecycle): PipelineStepState = when (state) {
  GoalAssessmentState.PENDING -> PipelineStepState.PENDING
  GoalAssessmentState.RUNNING -> if (lifecycle == Lifecycle.ACTIVE) PipelineStepState.ACTIVE else PipelineStepState.PENDING
  GoalAssessmentState.COMPLETED -> PipelineStepState.COMPLETE
  GoalAssessmentState.SKIPPED -> PipelineStepState.SKIPPED
}

private fun GoalAssessmentResult.fixStepState(lifecycle: Lifecycle): PipelineStepState =
  if (state == GoalAssessmentState.PENDING || state == GoalAssessmentState.SKIPPED) stepState(lifecycle) else PipelineStepState.COMPLETE

/** A scroll state that stays on the same row when pages arrive or rows disappear above it. */
@Composable
internal fun rememberAnchoredListState(
  identity: WorkIdentity,
  generation: Long,
  listKind: String,
  keys: List<String>,
): LazyListState {
  val state = remember(identity, generation, listKind) { LazyListState() }
  var anchor by remember(identity, generation, listKind) { mutableStateOf<Pair<String, Int>?>(null) }
  var previousKeys by remember(identity, generation, listKind) { mutableStateOf(keys) }
  LaunchedEffect(state) {
    snapshotFlow {
      state.layoutInfo.visibleItemsInfo.firstOrNull()?.let { info ->
        (info.key as? String)?.let { it to state.firstVisibleItemScrollOffset }
      }
    }.collect { visible -> anchor = visible }
  }
  LaunchedEffect(keys) {
    val saved = anchor
    if (saved != null) {
      val restoredKey = if (saved.first in keys) saved.first else {
        val oldIndex = previousKeys.indexOf(saved.first).coerceAtLeast(0)
        previousKeys.drop(oldIndex + 1).firstOrNull { it in keys }
          ?: previousKeys.take(oldIndex).asReversed().firstOrNull { it in keys }
      }
      val index = keys.indexOf(restoredKey)
      if (index >= 0) state.scrollToItem(index, saved.second)
    }
    previousKeys = keys
  }
  return state
}
