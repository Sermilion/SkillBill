package skillbill.engine.goalrunner.telemetry

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.goalrunner.model.GoalProgressEventDraft
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import java.time.Clock

internal const val GOAL_PLANNING_OPERATION_KIND = "goal_planning"

@Inject
class GoalPlanningProgressRecorder(
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val clock: Clock,
) {
  internal fun sink(
    parentWorkflowId: String,
    issueKey: String,
    agentId: String,
  ): FeatureTaskRuntimeRunEventSink =
    FeatureTaskRuntimeRunEventSink { event ->
      val kind =
        when (event) {
          is FeatureTaskRuntimeRunEvent.PhaseStarted -> GoalProgressEventKind.PHASE_STARTED
          is FeatureTaskRuntimeRunEvent.PhaseCompleted,
          is FeatureTaskRuntimeRunEvent.PhaseBlocked,
          is FeatureTaskRuntimeRunEvent.PhasePaused,
          -> GoalProgressEventKind.PHASE_COMPLETED
          else -> return@FeatureTaskRuntimeRunEventSink
        }
      val outcome =
        when (event) {
          is FeatureTaskRuntimeRunEvent.PhaseCompleted -> GoalProgressOutcome.SUCCEEDED
          is FeatureTaskRuntimeRunEvent.PhaseBlocked -> GoalProgressOutcome.FAILED
          is FeatureTaskRuntimeRunEvent.PhasePaused -> GoalProgressOutcome.CANCELLED
          else -> GoalProgressOutcome.NONE
        }
      val started = event as? FeatureTaskRuntimeRunEvent.PhaseStarted
      check(
        outcomeStore.recordProgressEvent(
          GoalRunnerProgressEventRecordRequest(
            workflowId = parentWorkflowId,
            issueKey = issueKey,
            draft =
              GoalProgressEventDraft(
                eventKind = kind,
                workflowId = parentWorkflowId,
                workflowPhase = "planning",
                processAlive = true,
                timestamp = clock.instant(),
                stepId = event.phaseId,
                operationKind = GOAL_PLANNING_OPERATION_KIND,
                outcome = outcome,
                agentId = started?.resolvedAgentId ?: agentId,
                launchedModel = started?.model,
                effort = started?.effort,
              ),
          ),
        ),
      ) { "Goal planning could not record progress for '$parentWorkflowId'." }
    }
}
