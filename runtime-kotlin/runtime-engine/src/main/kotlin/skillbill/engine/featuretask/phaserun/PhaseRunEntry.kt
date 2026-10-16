package skillbill.engine.featuretask.phaserun

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.parallel.runner.ParallelCodeReviewRunnerResultAssembly
import skillbill.application.telemetry.lifecycle.LifecycleTelemetryService
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeStepLaunchAssignmentFactory
import skillbill.engine.featuretask.lifecycle.execution.StepLaunchAssignmentInputs
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.phaserun.monitor.MonitorPreparation
import skillbill.engine.featuretask.phaserun.monitor.MonitorPreparationRequest
import skillbill.engine.featuretask.phaserun.monitor.StandaloneMonitorExecutionPreparer
import skillbill.engine.featuretask.phaserun.monitor.StandaloneMonitorLedger
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runloop.core.slotStepVerdictRule
import skillbill.engine.featuretask.runloop.core.strategySelectionFacts
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.error.featuretask.InMemorySkeletonDefinitionRequiredError
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.skeleton.SkeletonRunStateKind
import java.time.Clock
import java.util.UUID

@Inject
class PhaseRunEntry(
  private val strategies: PhaseStrategyLookup,
  private val gitOperations: WorkflowGitOperations,
  private val reviewResultAssembly: ParallelCodeReviewRunnerResultAssembly,
  private val lifecycleTelemetry: LifecycleTelemetryService,
  private val diagnostics: RuntimeDiagnostics,
  private val clock: Clock,
  private val intakeResolver: PhaseRunIntakeResolver,
  private val runLoopEntry: FeatureTaskRuntimeRunLoopEntry,
  private val agentRunLauncher: AgentRunLauncher,
  private val statusPublisherFactory: StandalonePhaseStatusPublisherFactory,
  private val standaloneMonitorPreparer: StandaloneMonitorExecutionPreparer,
) {
  fun run(request: PhaseRunRequest): PhaseRunResult {
    val invocationId = newInvocationId()
    return withStatus(request, invocationId, request.definitionId) { statusRequest ->
      runInternal(statusRequest, invocationId)
    }
  }

  internal fun runStandaloneMonitor(request: PhaseRunRequest): PhaseRunResult {
    val invocationId = newInvocationId()
    val definition =
      requireNotNull(SkeletonDefinition.preparedExecutionFor(request.definitionId)) {
        "Phase ${request.definitionId} has no prepared standalone execution."
      }
    val preparation =
      standaloneMonitorPreparer.prepare(
        MonitorPreparationRequest(request.repoRoot, request.intake, currentBranch(request)?.branch),
      )
    return when (preparation) {
      is MonitorPreparation.Prepared -> runPrepared(request, definition, invocationId, preparation)
      is MonitorPreparation.Completed ->
        PhaseRunResult.Completed(
          invocationId,
          emptyList(),
          null,
          preparation.pullRequest
            ?.let { "${it.targetKey} is already merged; there is nothing to monitor." }
            ?: "No open pull request matched the monitor target; there is nothing to monitor.",
        )
      is MonitorPreparation.Blocked ->
        monitorBlocked(
          request,
          invocationId,
          listOfNotNull(preparation.detail, preparation.path?.let { "Path: $it." }).joinToString(" "),
        )
      is MonitorPreparation.Refused ->
        monitorBlocked(
          request,
          invocationId,
          (listOf("Monitor target refused (${preparation.reason}): ${preparation.detail}") + preparation.candidateUrls)
            .joinToString(" "),
        )
      is MonitorPreparation.Failed -> monitorBlocked(request, invocationId, preparation.reason)
    }
  }

  fun runForGoal(
    request: PhaseRunRequest,
    parentWorkflowId: String,
    expectedBranch: String,
  ): PhaseRunResult = runInternal(request, parentWorkflowId, parentWorkflowId, expectedBranch)

  private fun runPrepared(
    request: PhaseRunRequest,
    definition: SkeletonDefinition,
    invocationId: String,
    prepared: MonitorPreparation.Prepared,
  ): PhaseRunResult {
    val ownedRequest =
      request.copy(
        definitionId = definition.id,
        repoRoot = prepared.context.worktree.path,
      )
    try {
      return withStatus(ownedRequest, invocationId, request.definitionId) { statusRequest ->
        runInternal(statusRequest, invocationId, standalone = prepared)
      }
    } finally {
      prepared.cleanup.settle()
    }
  }

  private fun withStatus(
    request: PhaseRunRequest,
    invocationId: String,
    publicPhaseId: String,
    body: (PhaseRunRequest) -> PhaseRunResult,
  ): PhaseRunResult {
    val publisher =
      statusPublisherFactory.forPhase(
        request.repoRoot,
        publicPhaseId,
        invocationId,
        request.invokedAgentId,
        request.launchArguments,
      )
    val statusRequest =
      request.copy(
        eventSink = statusPublisherFactory.compose(request.eventSink::emit, publisher),
        activityOutput = { stepName, activity ->
          publisher.publishActivity(stepName, activity)
          request.activityOutput?.invoke(stepName, activity)
        },
      )
    return runCatching { body(statusRequest) }
      .onSuccess(publisher::settle)
      .onFailure(publisher::settleFailure)
      .getOrThrow()
  }

  private fun monitorBlocked(
    request: PhaseRunRequest,
    invocationId: String,
    reason: String,
  ): PhaseRunResult.Blocked = PhaseRunResult.Blocked(invocationId, emptyList(), null, request.definitionId, reason)

  private fun runInternal(
    request: PhaseRunRequest,
    invocationId: String,
    workflowId: String = "",
    expectedBranch: String? = null,
    standalone: MonitorPreparation.Prepared? = null,
  ): PhaseRunResult {
    val definition = SkeletonDefinition.byId(request.definitionId)
    if (definition.runStateKind != SkeletonRunStateKind.IN_MEMORY) {
      throw InMemorySkeletonDefinitionRequiredError(definition.id)
    }
    val branch =
      standalone
        ?.context
        ?.let { FeatureTaskRuntimeResolvedBranch(branch = it.worktree.branch, baseBranch = it.target.base.ref) }
        ?: currentBranch(request)
    if (expectedBranch != null && branch?.branch != expectedBranch) {
      return PhaseRunResult.Blocked(
        invocationId,
        emptyList(),
        null,
        definition.id,
        "Goal monitoring requires branch '$expectedBranch'; current branch is '${branch?.branch.orEmpty()}'.",
      )
    }
    val intake = intakeResolver.resolve(definition, request, branch?.branch)
    val facts = InMemoryPhaseRunFacts(request, definition, intake, workflowId, standalone?.context)
    val selection = strategySelectionFacts(facts)
    val executionPlan =
      strategies.executionPlan(
        selection.copy(
          stepAssignments =
            FeatureTaskRuntimeStepLaunchAssignmentFactory.resolve(
              launcher = agentRunLauncher,
              lookup = strategies,
              facts = selection,
              inputs =
                StepLaunchAssignmentInputs(
                  invokedAgentId = request.invokedAgentId,
                  agentAssignment = facts.agentAssignment,
                  modelAssignment = facts.modelAssignment,
                ),
            ),
        ),
      )
    val progress =
      FeatureTaskRuntimeRunState(
        initialRecords = emptyMap(),
        transitions = executionPlan.phaseTraversal(),
        stepVerdictRule = slotStepVerdictRule(strategies, executionPlan, diagnostics),
        resumeRulesFn = strategies.resumeRules(executionPlan),
      )
    val records =
      InMemoryPhaseRunRecords(
        clock,
        branch,
        standalone?.let { StandaloneMonitorLedger(it.context, it.cleanup) },
      )
    val state =
      InMemoryPhaseRunState(
        facts = facts,
        executionPlan = executionPlan,
        progress = progress,
        records = records,
        telemetry = FeatureTaskRuntimeRunObservability(records, facts, diagnostics),
        invocationId = invocationId,
        strategies = strategies,
        reviewResultAssembly = reviewResultAssembly,
        lifecycleTelemetry = lifecycleTelemetry,
        clock = clock,
        runLoopEntry = runLoopEntry,
      )
    val report = runLoopEntry.run(runLoopEntry.context(facts, state))
    return resultOf(report, state, records)
  }

  private fun resultOf(
    report: FeatureTaskRuntimeRunReport,
    state: InMemoryPhaseRunState,
    records: InMemoryPhaseRunRecords,
  ): PhaseRunResult =
    when (report) {
      is FeatureTaskRuntimeRunReport.Completed ->
        PhaseRunResult.Completed(
          invocationId = state.invocationId,
          completedStepIds = report.completedPhaseIds,
          reviewResult = state.reviewResult,
          value = report.completedPhaseIds.lastOrNull()?.let { id -> records.loadPhaseRecords("")[id]?.outputArtifact },
          completedOutputs = completedOutputs(report.completedPhaseIds, records),
          acceptedOutput = report.completedPhaseIds.lastOrNull()?.let { records.acceptedOutputs[it] },
        )
      is FeatureTaskRuntimeRunReport.Blocked ->
        blocked(state, report.completedPhaseIds, report.lastIncompletePhase, report.blockedReason, records)
      is FeatureTaskRuntimeRunReport.Paused ->
        blocked(state, report.completedPhaseIds, report.pausedPhase, report.pauseReason, records)
      is FeatureTaskRuntimeRunReport.Decomposed ->
        PhaseRunResult.Completed(
          invocationId = state.invocationId,
          completedStepIds = report.completedPhaseIds,
          reviewResult = state.reviewResult,
          value = report.reason,
          specBundle =
            PhaseRunSpecBundle(
              parentSpecPath = report.parentSpecPath,
              decompositionManifestPath = report.decompositionManifestPath,
              subtaskSpecPaths = report.subtaskSpecPaths,
            ),
        )
    }

  private fun currentBranch(request: PhaseRunRequest): FeatureTaskRuntimeResolvedBranch? =
    (gitOperations.currentBranch(request.repoRoot) as? WorkflowGitOperationResult.Ok)
      ?.value
      ?.trim()
      ?.takeIf { branch -> branch.isNotBlank() && branch != DETACHED_HEAD }
      ?.let(::FeatureTaskRuntimeResolvedBranch)

  private fun blocked(
    state: InMemoryPhaseRunState,
    completedStepIds: List<String>,
    stepId: String,
    reason: String,
    records: InMemoryPhaseRunRecords,
  ): PhaseRunResult.Blocked =
    PhaseRunResult.Blocked(
      state.invocationId,
      completedStepIds,
      state.reviewResult,
      stepId,
      reason,
      completedOutputs(completedStepIds, records),
    )

  private fun completedOutputs(
    stepIds: List<String>,
    records: InMemoryPhaseRunRecords,
  ): List<String> {
    val outputs = records.loadPhaseRecords("")
    return stepIds.mapNotNull { id -> outputs[id]?.outputArtifact?.takeIf(String::isNotBlank) }
  }
}

private fun newInvocationId(): String = "$INVOCATION_ID_PREFIX${UUID.randomUUID()}"

private const val INVOCATION_ID_PREFIX = "phr-"
private const val DETACHED_HEAD = "HEAD"
