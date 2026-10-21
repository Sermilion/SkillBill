package skillbill.engine.featuretask.phaserun

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.parallel.runner.ParallelCodeReviewRunnerResultAssembly
import skillbill.application.telemetry.lifecycle.LifecycleTelemetryService
import skillbill.engine.diagnostics.block.BlockIncidentEntryReconciler
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeStepLaunchAssignmentFactory
import skillbill.engine.featuretask.lifecycle.execution.StepLaunchAssignmentInputs
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.phaserun.monitor.MonitorAttemptTarget
import skillbill.engine.featuretask.phaserun.monitor.MonitorPreparation
import skillbill.engine.featuretask.phaserun.monitor.MonitorPreparationRequest
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
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.monitor.model.MonitorExpectedOwnerToken
import skillbill.ports.monitor.model.MonitorOutcomeKind
import skillbill.ports.monitor.model.MonitorPrimaryOutcome
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import skillbill.workflow.taskruntime.model.skeleton.PhaseRunHarness
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
  private val standaloneRunners: StandalonePhaseRunners,
  private val blockIncidentReconciler: BlockIncidentEntryReconciler,
) {
  fun run(request: PhaseRunRequest): PhaseRunResult {
    blockIncidentReconciler.reconcileOnEntry()
    if (SkeletonDefinition.preparedExecutionFor(request.definitionId) != null) return runStandaloneMonitor(request)
    val invocationId = newInvocationId()
    return withStatus(request, invocationId, request.definitionId) { statusRequest ->
      if (SkeletonDefinition.runHarnessOf(request.definitionId) == PhaseRunHarness.DEVICE_QA) {
        standaloneRunners.runQa(statusRequest, invocationId) { runInternal(it, invocationId) }
      } else {
        runInternal(statusRequest, invocationId)
      }
    }
  }

  internal fun runStandaloneMonitor(request: PhaseRunRequest): PhaseRunResult {
    val invocationId = newInvocationId()
    val definition =
      requireNotNull(SkeletonDefinition.preparedExecutionFor(request.definitionId)) {
        "Phase ${request.definitionId} has no prepared standalone execution."
      }
    val branch = currentBranch(request)?.branch
    val selector = request.intake?.trim()?.takeIf(String::isNotEmpty) ?: branch?.let { "branch $it" } ?: "no target"
    if (request.recoverStaleOwner != null && request.intake.isNullOrBlank()) {
      return monitorBlocked(
        request,
        invocationId,
        "Stale owner recovery needs an explicit pull request target.",
        listOf("Selector: $selector"),
      )
    }
    val preparation =
      standaloneRunners.prepareMonitor(
        MonitorPreparationRequest(
          request.repoRoot,
          request.intake,
          branch,
          request.recoverStaleOwner?.let(::MonitorExpectedOwnerToken),
        ),
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
            ?: "No open pull request matched $selector; there is nothing to monitor.",
          targetReport = earlyReport(selector, preparation.pullRequest),
        )
      is MonitorPreparation.Blocked ->
        monitorBlocked(
          request,
          invocationId,
          listOfNotNull(preparation.detail, preparation.path?.let { "Path: $it." }).joinToString(" "),
          earlySetupReport(selector, preparation.pullRequest, preparation.settlement),
          preparation.attempt,
        )
      is MonitorPreparation.Refused ->
        monitorBlocked(
          request,
          invocationId,
          (listOf("Monitor target refused (${preparation.reason}): ${preparation.detail}") + preparation.candidateUrls)
            .joinToString(" "),
          earlyReport(selector, null),
        )
      is MonitorPreparation.Failed ->
        monitorBlocked(
          request,
          invocationId,
          preparation.reason,
          earlySetupReport(selector, preparation.pullRequest, preparation.settlement),
          preparation.attempt,
        )
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
      return withStatus(ownedRequest, invocationId, request.definitionId, prepared) { statusRequest ->
        runInternal(statusRequest, invocationId, standalone = prepared)
      }
    } finally {
      prepared.cleanup.settle(
        MonitorPrimaryOutcome(MonitorOutcomeKind.RUN_FAILED, "The monitor run ended before its outcome was recorded."),
      )
    }
  }

  private fun withStatus(
    request: PhaseRunRequest,
    invocationId: String,
    publicPhaseId: String,
    monitor: MonitorPreparation.Prepared? = null,
    body: (PhaseRunRequest) -> PhaseRunResult,
  ): PhaseRunResult {
    val publisher =
      monitor?.let {
        statusPublisherFactory.forMonitor(
          MonitorAttemptTarget(
            it.context.target,
            it.context.callerRoot,
            it.context.primaryRoot,
            it.context.worktree.path,
          ),
          publicPhaseId,
          invocationId,
          request.invokedAgentId,
          request.launchArguments,
        )
      } ?: statusPublisherFactory.forPhase(
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
    if (SkeletonDefinition.runHarnessOf(publicPhaseId) == PhaseRunHarness.DEVICE_QA) {
      publisher.recordQaOutcome(StandaloneQaOutcome.ATTEMPTED)
    }
    monitor?.cleanup?.onSettled { settlement ->
      runCatching { publisher.recordMonitorCleanup(settlement.toStatusCleanup()) }
    }
    val outcome = runCatching { body(statusRequest) }
    val settlement = monitor?.cleanup?.settle(primaryOutcomeOf(outcome))
    return outcome
      .map { result -> if (monitor == null) result else result.withTargetReport(monitor.context, settlement) }
      .onSuccess(publisher::settle)
      .onFailure(publisher::settleFailure)
      .getOrElse { failure -> throw monitor?.let { monitorRunFailure(failure, it.context, settlement) } ?: failure }
  }

  private fun monitorBlocked(
    request: PhaseRunRequest,
    invocationId: String,
    reason: String,
    targetReport: List<String>,
    attempt: MonitorAttemptTarget? = null,
  ): PhaseRunResult.Blocked {
    val result =
      PhaseRunResult.Blocked(
        invocationId,
        emptyList(),
        null,
        request.definitionId,
        reason,
        targetReport = targetReport,
      )
    attempt?.let {
      statusPublisherFactory.forMonitor(
        it,
        request.definitionId,
        invocationId,
        request.invokedAgentId,
        request.launchArguments,
      ).settle(result)
    }
    return result
  }

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
    if (expectedBranch != null && branch?.branch != expectedBranch) {
      return PhaseRunResult.Blocked(
        invocationId,
        emptyList(),
        null,
        executionPlan.selectedEntryStepIds.first(),
        "Goal finalization requires branch '$expectedBranch'; current branch is '${branch?.branch.orEmpty()}'.",
      )
    }
    val progress =
      FeatureTaskRuntimeRunState(
        initialRecords = emptyMap(),
        transitions = executionPlan.phaseTraversal(),
        stepVerdictRule = slotStepVerdictRule(strategies, executionPlan, diagnostics),
        resumeRulesFn = strategies.resumeRules(executionPlan),
      )
    val records = inMemoryRecords(request, workflowId, branch, standalone)
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

  private fun inMemoryRecords(
    request: PhaseRunRequest,
    workflowId: String,
    branch: FeatureTaskRuntimeResolvedBranch?,
    standalone: MonitorPreparation.Prepared?,
  ): InMemoryPhaseRunRecords =
    InMemoryPhaseRunRecords(
      clock,
      branch,
      standalone?.let { StandaloneMonitorLedger(it.context, it.cleanup) },
      request.recordedGoalQaOutcome?.let { recorded ->
        QaRepairState.resumedAfter(workflowId, recorded.qaFixAttempts, recorded.testedTreeIdentity)
      },
    )

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
