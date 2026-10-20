package skillbill.engine.goalrunner.qa

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.featuretask.phaserun.PhaseRunEntry
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.goalrunner.model.GoalProgressEventDraft
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.model.GoalSubtaskQaSummary
import skillbill.engine.goalrunner.monitoring.GOAL_FINALIZATION_OPERATION_KIND
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.decomposition.branchForFinalPullRequest
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import java.time.Clock

@Inject
class DefaultGoalRunnerQaGate(
  private val phaseRunEntry: PhaseRunEntry,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val gitOperations: WorkflowGitOperations,
  private val invariantsSource: FeatureTaskRuntimeRunInvariantsSource,
  private val clock: Clock,
  private val activityStampWriter: AgentActivityStampWriter,
) : GoalRunnerQaGate {
  override fun run(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalQaGateResult {
    if (childrenAlreadyRanQa(state)) return GoalQaGateResult.Skipped(GoalQaSkipReason.SUBTASKS_ALREADY_RAN_QA)
    val parentOutcome = outcomeStore.recordedGoalQaOutcome(state.parentWorkflowId)
    val currentTree = gitOperations.repositoryTreeIdentity(request.repoRoot)
    val settledOutcome = parentOutcome?.outcome
    if (
      settledOutcome in setOf(StandaloneQaOutcome.PASSED, StandaloneQaOutcome.SKIPPED) &&
      currentTree is WorkflowGitOperationResult.Ok && currentTree.value == parentOutcome?.testedTreeIdentity
    ) {
      return GoalQaGateResult.Skipped(GoalQaSkipReason.ALREADY_SETTLED_ON_TREE)
    }
    val invariants =
      when (
        val read =
          invariantsSource.read(
            resolvedParentSpecPath(request.repoRoot, Path.of(state.manifest.parentSpecPath)),
          )
      ) {
        is FeatureTaskRuntimeRunInvariantsRead.Read -> read.invariants
        is FeatureTaskRuntimeRunInvariantsRead.Rejected ->
          return GoalQaGateResult.Warned(
            "QA did not run: could not read parent spec acceptance criteria: ${read.reason}",
          )
      }
    val branch = state.manifest.featureBranch.orEmpty().ifBlank { state.manifest.branchForFinalPullRequest() }
    val result =
      phaseRunEntry.runForGoal(
        PhaseRunRequest(
          definitionId = SkeletonDefinition.GOAL_QA.id,
          repoRoot = request.repoRoot,
          invokedAgentId = request.configuredAgentOverrideId ?: request.invokedAgentId,
          intake = request.issueKey,
          timeout = request.timeout,
          agentAddonSelection = request.agentAddonSelection,
          modelAssignment = request.modelAssignment,
          qaSelection = request.qaSelection ?: FeatureTaskRuntimeQaSelection.AUTO,
          acceptanceCriteria = invariants.acceptanceCriteria,
          eventSink = FeatureTaskRuntimeRunEventSink { event -> record(state, request, event) },
          activityOutput = { stepName, activity -> recordActivity(state, request, stepName, activity) },
          activityStampSink = activityStampWriter.sink(state.parentWorkflowId, parentWorkflowId = null),
        ),
        state.parentWorkflowId,
        branch,
      )
    return when (result) {
      is PhaseRunResult.Completed -> GoalQaGateResult.Passed
      is PhaseRunResult.Blocked -> GoalQaGateResult.Warned("QA stopped at '${result.stepId}': ${result.reason}")
    }
  }

  private fun childrenAlreadyRanQa(state: GoalRunnerManifestState): Boolean {
    val workflows = state.manifest.subtasks.mapNotNull { it.workflowId?.takeIf(String::isNotBlank) }
    return workflows.isNotEmpty() &&
      workflows.all { workflowId ->
        outcomeStore.hasQaComposedPlan(workflowId) &&
          outcomeStore.recordedQaOutcome(workflowId) is GoalSubtaskQaSummary.Recorded
      }
  }

  private fun record(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    event: FeatureTaskRuntimeRunEvent,
  ) {
    val phaseId: String
    val kind: GoalProgressEventKind
    val outcome: GoalProgressOutcome
    when (event) {
      is FeatureTaskRuntimeRunEvent.PhaseStarted -> {
        phaseId = event.phaseId
        kind = GoalProgressEventKind.PHASE_STARTED
        outcome = GoalProgressOutcome.NONE
      }
      is FeatureTaskRuntimeRunEvent.PhaseCompleted -> {
        phaseId = event.phaseId
        kind = GoalProgressEventKind.PHASE_COMPLETED
        outcome = GoalProgressOutcome.SUCCEEDED
      }
      is FeatureTaskRuntimeRunEvent.PhaseBlocked -> {
        phaseId = event.phaseId
        kind = GoalProgressEventKind.PHASE_COMPLETED
        outcome = GoalProgressOutcome.FAILED
      }
      is FeatureTaskRuntimeRunEvent.PhasePaused -> {
        phaseId = event.phaseId
        kind = GoalProgressEventKind.PHASE_COMPLETED
        outcome = GoalProgressOutcome.CANCELLED
      }
      else -> return
    }
    recordProgress(state, request, progressDraft(state, request, phaseId, kind, outcome))
    request.outputSink.write(AgentRunOutputStream.STDERR, "$phaseId: ${kind.wireValue}\n")
  }

  private fun recordActivity(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    stepName: String,
    activity: String,
  ) {
    val boundedActivity = activity.filterNot(Char::isISOControl).take(MAX_ACTIVITY_LENGTH)
    recordProgress(
      state,
      request,
      progressDraft(state, request, stepName, GoalProgressEventKind.OPERATION_HEARTBEAT, GoalProgressOutcome.NONE).copy(
        operationName = boundedActivity.take(MAX_OPERATION_NAME_LENGTH),
        expectedLong = true,
      ),
    )
    request.outputSink.write(AgentRunOutputStream.STDERR, "$boundedActivity\n")
  }

  private fun recordProgress(
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

  private fun progressDraft(
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
}

private const val MAX_ACTIVITY_LENGTH = 500
private const val MAX_OPERATION_NAME_LENGTH = 160
