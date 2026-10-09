package skillbill.engine.goalrunner.monitoring

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.featuretask.phaserun.PhaseRunEntry
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.goalrunner.model.GoalProgressEventDraft
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.time.Clock

fun interface GoalRunnerCiMonitor {
  fun monitor(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): PhaseRunResult
}

@Inject
class DefaultGoalRunnerCiMonitor(
  private val phaseRunEntry: PhaseRunEntry,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val clock: Clock,
) : GoalRunnerCiMonitor {
  override fun monitor(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): PhaseRunResult =
    phaseRunEntry.runForGoal(
      PhaseRunRequest(
        definitionId = SkeletonDefinition.MONITOR.id,
        repoRoot = request.repoRoot,
        invokedAgentId = request.configuredAgentOverrideId ?: request.invokedAgentId,
        intake = request.issueKey,
        timeout = request.timeout,
        agentAddonSelection = request.agentAddonSelection,
        modelAssignment = request.modelAssignment,
        eventSink = FeatureTaskRuntimeRunEventSink { event -> record(state, request, event) },
      ),
      state.parentWorkflowId,
      state.manifest.featureBranch.orEmpty(),
    )

  private fun record(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    event: FeatureTaskRuntimeRunEvent,
  ) {
    val kind =
      when (event) {
        is FeatureTaskRuntimeRunEvent.PhaseStarted -> GoalProgressEventKind.PHASE_STARTED
        is FeatureTaskRuntimeRunEvent.PhaseCompleted,
        is FeatureTaskRuntimeRunEvent.PhaseBlocked,
        is FeatureTaskRuntimeRunEvent.PhasePaused,
        -> GoalProgressEventKind.PHASE_COMPLETED
        else -> return
      }
    val outcome =
      when (event) {
        is FeatureTaskRuntimeRunEvent.PhaseCompleted -> GoalProgressOutcome.SUCCEEDED
        is FeatureTaskRuntimeRunEvent.PhaseBlocked -> GoalProgressOutcome.FAILED
        is FeatureTaskRuntimeRunEvent.PhasePaused -> GoalProgressOutcome.CANCELLED
        else -> GoalProgressOutcome.NONE
      }
    val persisted =
      outcomeStore.recordProgressEvent(
        GoalRunnerProgressEventRecordRequest(
          workflowId = state.parentWorkflowId,
          issueKey = request.issueKey,
          draft =
            GoalProgressEventDraft(
              eventKind = kind,
              workflowId = state.parentWorkflowId,
              workflowPhase = event.phaseId,
              processAlive = true,
              timestamp = clock.instant(),
              stepId = event.phaseId,
              operationKind = GOAL_FINALIZATION_OPERATION_KIND,
              outcome = outcome,
            ),
        ),
      )
    check(persisted) { "Goal monitoring could not record progress for '${state.parentWorkflowId}'." }
    request.outputSink.write(AgentRunOutputStream.STDERR, "${event.phaseId}: ${kind.wireValue}\n")
  }
}

const val GOAL_FINALIZATION_OPERATION_KIND: String = "goal_finalization"
