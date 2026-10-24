package skillbill.engine.goalrunner.qa

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.featuretask.phaserun.PhaseRunEntry
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolution
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolver
import skillbill.engine.featuretask.qa.report.FeatureTaskQaOutcomeRecorder
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.engine.goalrunner.model.GoalProgressEventDraft
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.model.GoalSubtaskQaSummary
import skillbill.engine.goalrunner.monitoring.GOAL_FINALIZATION_OPERATION_KIND
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.qa.isQaFailure
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskQaFailureKind
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.decomposition.branchForFinalPullRequest
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.CancellationException

@Inject
class DefaultGoalRunnerQaGate(
  private val phaseRunEntry: PhaseRunEntry,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val gitOperations: WorkflowGitOperations,
  private val invariantsSource: FeatureTaskRuntimeRunInvariantsSource,
  private val clock: Clock,
  private val activityStampWriter: AgentActivityStampWriter,
  private val qaOutcomes: FeatureTaskQaOutcomeRecorder,
  private val capabilities: QaCapabilityResolver,
  private val projectStore: ProjectStorePort,
  private val diagnostics: RuntimeDiagnostics,
) : GoalRunnerQaGate {
  override fun run(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalQaGateResult =
    runCatching { runQa(state, request) }.getOrElse { failure -> containFailure(state, containable(failure)) }

  private fun containable(failure: Throwable): Exception =
    if (failure is Exception && failure !is CancellationException && failure !is InterruptedException) {
      failure
    } else {
      throw failure
    }

  private fun containFailure(
    state: GoalRunnerManifestState,
    failure: Exception,
  ): GoalQaGateResult.Warned {
    val environment = (failure as? SkillBillRuntimeException)?.isQaFailure == true
    val message = failure.message?.takeIf(String::isNotBlank) ?: failure.javaClass.simpleName
    val reason =
      if (environment) {
        "QA could not complete: $message"
      } else {
        "QA could not complete because of a runtime defect: ${failure.javaClass.simpleName}: $message"
      }
    RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "goal_qa_gate: $reason", failure)
    recordBestEffort(state) {
      qaOutcomes.recordContainedFailure(
        state.parentWorkflowId,
        if (environment) FeatureTaskQaFailureKind.ENVIRONMENT else FeatureTaskQaFailureKind.DEFECT,
        reason,
      )
    }
    return GoalQaGateResult.Warned(reason)
  }

  private fun recordBestEffort(
    state: GoalRunnerManifestState,
    record: () -> Unit,
  ) {
    runCatching(record).onFailure { recordingFailure ->
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "goal_qa_gate: could not record the QA warning for '${state.parentWorkflowId}'.",
        containable(recordingFailure),
      )
    }
  }

  private fun runQa(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalQaGateResult {
    if (childrenAlreadyRanQa(state)) return GoalQaGateResult.Skipped(GoalQaSkipReason.SUBTASKS_ALREADY_RAN_QA)
    val parentOutcome = outcomeStore.recordedGoalQaOutcome(state.parentWorkflowId)
    val currentTree = gitOperations.repositoryTreeIdentity(request.repoRoot)
    val settledOutcome = parentOutcome?.outcome
    val skipReason =
      when {
        settledOutcome in setOf(StandaloneQaOutcome.PASSED, StandaloneQaOutcome.SKIPPED) &&
          currentTree is WorkflowGitOperationResult.Ok && currentTree.value == parentOutcome?.testedTreeIdentity ->
          GoalQaSkipReason.ALREADY_SETTLED_ON_TREE
        capabilities.resolve(request.repoRoot) is QaCapabilityResolution.Absent -> GoalQaSkipReason.UNSUPPORTED_TARGET
        else -> null
      }
    if (skipReason != null) return GoalQaGateResult.Skipped(skipReason)
    val invariants =
      when (
        val read =
          invariantsSource.read(
            resolvedParentSpecPath(
              request.repoRoot,
              Path.of(state.manifest.parentSpecPath),
              projectStore.resolve(request.repoRoot),
            ),
          )
      ) {
        is FeatureTaskRuntimeRunInvariantsRead.Read -> read.invariants
        is FeatureTaskRuntimeRunInvariantsRead.Rejected ->
          return containedWarning(
            state,
            "QA did not run: could not read parent spec acceptance criteria: ${read.reason}",
          )
      }
    val branch = state.manifest.featureBranch.orEmpty().ifBlank { state.manifest.branchForFinalPullRequest() }
    val goalQaRequest =
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
        recordedGoalQaOutcome = parentOutcome,
      )
    val qaResult = phaseRunEntry.runForGoal(goalQaRequest, state.parentWorkflowId, branch)
    return settleAfterQa(state, request, GoalQaRun(goalQaRequest, branch, currentTree, qaResult))
  }

  private fun settleAfterQa(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    run: GoalQaRun,
  ): GoalQaGateResult {
    val buildWarning = buildAfterQaFixWarning(run.result)
    val qaWarning =
      buildWarning ?: (run.result as? PhaseRunResult.Blocked)?.let { "QA stopped at '${it.stepId}': ${it.reason}" }
    qaWarning?.let { recordContainedEnvironmentFailure(state, it) }
    val treeAfterQa = gitOperations.repositoryTreeIdentity(request.repoRoot)
    val validationWarnings =
      if (sameTree(run.startTree, treeAfterQa)) emptyList() else validateAfterQa(state, request, run, buildWarning)
    val warnings = listOfNotNull(qaWarning) + validationWarnings
    return if (warnings.isEmpty()) GoalQaGateResult.Passed else GoalQaGateResult.Warned(warnings.joinToString(" "))
  }

  private fun validateAfterQa(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    run: GoalQaRun,
    buildWarning: String?,
  ): List<String> {
    val validation =
      phaseRunEntry.runForGoal(
        run.request.copy(definitionId = SkeletonDefinition.VALIDATION.id, recordedGoalQaOutcome = null),
        state.parentWorkflowId,
        run.branch,
      )
    val validationWarning =
      (validation as? PhaseRunResult.Blocked)?.let { blocked ->
        "Validation after QA stopped at '${blocked.stepId}': ${blocked.reason}"
      }
    validationWarning?.let { recordContainedEnvironmentFailure(state, it) }
    val changedWarning = codeChangedAfterQa(state, request).takeIf { buildWarning == null }
    changedWarning?.let { recordBestEffort(state) { qaOutcomes.recordWarning(state.parentWorkflowId, it) } }
    return listOfNotNull(validationWarning, changedWarning)
  }

  private fun containedWarning(
    state: GoalRunnerManifestState,
    warning: String,
  ): GoalQaGateResult.Warned {
    recordContainedEnvironmentFailure(state, warning)
    return GoalQaGateResult.Warned(warning)
  }

  private fun recordContainedEnvironmentFailure(
    state: GoalRunnerManifestState,
    reason: String,
  ) = recordBestEffort(state) {
    qaOutcomes.recordContainedFailure(state.parentWorkflowId, FeatureTaskQaFailureKind.ENVIRONMENT, reason)
  }

  private fun buildAfterQaFixWarning(result: PhaseRunResult): String? {
    val blocked = result as? PhaseRunResult.Blocked ?: return null
    if (blocked.stepId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD) return null
    return "qa_fix left the app without a passing build, so QA did not re-test the fix. The build gate allows " +
      "${FeatureTaskRuntimeBuildGateCoordinator.MAX_REPAIR_TURNS} build repairs and stopped: ${blocked.reason}"
  }

  private fun sameTree(
    before: WorkflowGitOperationResult,
    after: WorkflowGitOperationResult,
  ): Boolean =
    before is WorkflowGitOperationResult.Ok && after is WorkflowGitOperationResult.Ok &&
      before.value.trim() == after.value.trim()

  private fun codeChangedAfterQa(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): String? {
    val settled = outcomeStore.recordedGoalQaOutcome(state.parentWorkflowId) ?: return null
    val tested = settled.testedTreeIdentity?.takeIf { settled.outcome != StandaloneQaOutcome.SKIPPED } ?: return null
    val current = gitOperations.repositoryTreeIdentity(request.repoRoot)
    if (current is WorkflowGitOperationResult.Ok && current.value.trim() == tested) return null
    return "validate changed code after QA settled; QA does not re-run, so the published change differs from the " +
      "tree QA tested ($tested)."
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
    val started = event as? FeatureTaskRuntimeRunEvent.PhaseStarted
    recordProgress(
      state,
      request,
      progressDraft(state, request, phaseId, kind, outcome).copy(
        launchedModel = started?.model,
        effort = started?.effort,
      ),
    )
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

private data class GoalQaRun(
  val request: PhaseRunRequest,
  val branch: String,
  val startTree: WorkflowGitOperationResult,
  val result: PhaseRunResult,
)

private const val MAX_ACTIVITY_LENGTH = 500
private const val MAX_OPERATION_NAME_LENGTH = 160
