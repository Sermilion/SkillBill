package skillbill.engine.goalrunner.assessment

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.goalrunner.model.GoalProgressEventDraft
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.monitoring.GOAL_FINALIZATION_OPERATION_KIND
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import java.time.Clock

internal class GoalAssessmentProgress(
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun eventSink(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): FeatureTaskRuntimeRunEventSink =
    FeatureTaskRuntimeRunEventSink { event ->
      val (phaseId, kind, outcome) =
        when (event) {
          is FeatureTaskRuntimeRunEvent.PhaseStarted ->
            Triple(event.phaseId, GoalProgressEventKind.PHASE_STARTED, GoalProgressOutcome.NONE)
          is FeatureTaskRuntimeRunEvent.PhaseCompleted ->
            Triple(event.phaseId, GoalProgressEventKind.PHASE_COMPLETED, GoalProgressOutcome.SUCCEEDED)
          is FeatureTaskRuntimeRunEvent.PhaseBlocked ->
            Triple(event.phaseId, GoalProgressEventKind.PHASE_COMPLETED, GoalProgressOutcome.FAILED)
          is FeatureTaskRuntimeRunEvent.PhasePaused ->
            Triple(event.phaseId, GoalProgressEventKind.PHASE_COMPLETED, GoalProgressOutcome.CANCELLED)
          else -> return@FeatureTaskRuntimeRunEventSink
        }
      val started = event as? FeatureTaskRuntimeRunEvent.PhaseStarted
      val phaseDraft = draft(state, request, phaseId, kind, outcome)
      record(state, request, phaseDraft.copy(launchedModel = started?.model, effort = started?.effort))
      request.outputSink.write(AgentRunOutputStream.STDERR, "$phaseId: ${kind.wireValue}\n")
    }

  fun activityOutput(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): (String, String) -> Unit =
    { stepName, activity ->
      val bounded = activity.filterNot(Char::isISOControl).take(MAX_ACTIVITY_LENGTH)
      val kind = GoalProgressEventKind.OPERATION_HEARTBEAT
      val heartbeat = draft(state, request, stepName, kind, GoalProgressOutcome.NONE)
      val named = heartbeat.copy(operationName = bounded.take(MAX_OPERATION_NAME_LENGTH), expectedLong = true)
      record(state, request, named)
      request.outputSink.write(AgentRunOutputStream.STDERR, "$bounded\n")
    }

  private fun record(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    draft: GoalProgressEventDraft,
  ) {
    val recordRequest =
      GoalRunnerProgressEventRecordRequest(
        workflowId = state.parentWorkflowId,
        issueKey = request.issueKey,
        draft = draft,
      )
    if (!outcomeStore.recordProgressEvent(recordRequest)) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "goal_assessment_gate: could not record progress for '${state.parentWorkflowId}'.",
      )
    }
  }

  private fun draft(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    phaseId: String,
    kind: GoalProgressEventKind,
    outcome: GoalProgressOutcome,
  ): GoalProgressEventDraft =
    GoalProgressEventDraft(
      eventKind = kind,
      workflowId = state.parentWorkflowId,
      workflowPhase = phaseId,
      processAlive = true,
      timestamp = clock.instant(),
      stepId = phaseId,
      operationKind = GOAL_FINALIZATION_OPERATION_KIND,
      outcome = outcome,
      agentId = request.configuredAgentOverrideId ?: request.invokedAgentId,
    )

  private companion object {
    const val MAX_ACTIVITY_LENGTH = 500
    const val MAX_OPERATION_NAME_LENGTH = 160
  }
}
