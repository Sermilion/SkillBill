package skillbill.engine.goalrunner.qa

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalProgressEventDraft
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import java.time.Clock

internal fun recordGoalQaEvent(
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  clock: Clock,
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
  event: FeatureTaskRuntimeRunEvent,
) {
  val phaseId: String
  val agentId: String
  val kind: GoalProgressEventKind
  val outcome: GoalProgressOutcome
  when (event) {
    is FeatureTaskRuntimeRunEvent.PhaseStarted -> {
      phaseId = event.phaseId
      agentId = event.resolvedAgentId
      kind = GoalProgressEventKind.PHASE_STARTED
      outcome = GoalProgressOutcome.NONE
    }
    is FeatureTaskRuntimeRunEvent.PhaseCompleted -> {
      phaseId = event.phaseId
      agentId = event.resolvedAgentId
      kind = GoalProgressEventKind.PHASE_COMPLETED
      outcome = GoalProgressOutcome.SUCCEEDED
    }
    is FeatureTaskRuntimeRunEvent.PhaseBlocked -> {
      phaseId = event.phaseId
      agentId = event.resolvedAgentId
      kind = GoalProgressEventKind.PHASE_COMPLETED
      outcome = GoalProgressOutcome.FAILED
    }
    is FeatureTaskRuntimeRunEvent.PhasePaused -> {
      phaseId = event.phaseId
      agentId = event.resolvedAgentId
      kind = GoalProgressEventKind.PHASE_COMPLETED
      outcome = GoalProgressOutcome.CANCELLED
    }
    else -> return
  }
  val started = event as? FeatureTaskRuntimeRunEvent.PhaseStarted
  recordProgress(
    outcomeStore,
    state,
    request,
    clock.goalQaProgressDraft(state, agentId, phaseId, kind, outcome).copy(
      launchedModel = started?.model,
      effort = started?.effort,
    ),
  )
  request.outputSink.write(AgentRunOutputStream.STDERR, "$phaseId: ${kind.wireValue}\n")
}

internal data class GoalQaActivity(
  val agentId: String,
  val phaseId: String,
  val text: String,
)

internal fun recordGoalQaActivity(
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  clock: Clock,
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
  activity: GoalQaActivity,
) {
  val boundedActivity = activity.text.filterNot(Char::isISOControl).take(MAX_ACTIVITY_LENGTH)
  recordProgress(
    outcomeStore,
    state,
    request,
    clock.goalQaProgressDraft(
      state,
      activity.agentId,
      activity.phaseId,
      GoalProgressEventKind.OPERATION_HEARTBEAT,
      GoalProgressOutcome.NONE,
    ).copy(
      operationName = boundedActivity.take(MAX_OPERATION_NAME_LENGTH),
      expectedLong = true,
    ),
  )
  request.outputSink.write(AgentRunOutputStream.STDERR, "$boundedActivity\n")
}

private fun recordProgress(
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
  draft: GoalProgressEventDraft,
) {
  check(
    outcomeStore.recordProgressEvent(
      GoalRunnerProgressEventRecordRequest(
        workflowId = state.parentWorkflowId,
        issueKey = request.issueKey,
        draft = draft,
      ),
    ),
  ) { "Goal finalization could not record progress for '${state.parentWorkflowId}'." }
}
