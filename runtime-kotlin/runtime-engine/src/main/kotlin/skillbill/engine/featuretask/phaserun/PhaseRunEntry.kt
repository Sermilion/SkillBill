package skillbill.engine.featuretask.phaserun

import me.tatarka.inject.annotations.Inject
import skillbill.config.model.WorkflowAgentAssignmentPolicyMode
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.phaserun.monitor.MonitorAttemptTarget
import skillbill.engine.featuretask.phaserun.monitor.MonitorPreparation
import skillbill.engine.featuretask.phaserun.monitor.MonitorPreparationRequest
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runloop.qualitygate.goalBuildDefinitionId
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.catchingNonCancellation
import skillbill.error.core.failureCodeLabel
import skillbill.error.core.isCooperativeCancellation
import skillbill.error.featuretask.InMemorySkeletonDefinitionRequiredError
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.quality.GoalQualityAcceptedSourceObservation
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.monitor.model.MonitorExpectedOwnerToken
import skillbill.ports.monitor.model.MonitorOutcomeKind
import skillbill.ports.monitor.model.MonitorPrimaryOutcome
import skillbill.ports.workflow.branchworktree.BranchWorktreeResources
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolder
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolderKind
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeRelease
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.qa.QaOpenFinding
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import skillbill.workflow.taskruntime.model.skeleton.PhaseRunHarness
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.skeleton.SkeletonRunStateKind
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CancellationException

@Inject
public class PhaseRunEntry(
  private val stateFactory: InMemoryPhaseRunStateFactory,
  private val diagnostics: RuntimeDiagnostics,
  private val clock: Clock,
  private val intakeResolver: PhaseRunIntakeResolver,
  private val runLoopEntry: FeatureTaskRuntimeRunLoopEntry,
  private val statusPublisherFactory: StandalonePhaseStatusPublisherFactory,
  private val standaloneRunners: StandalonePhaseRunners,
  private val plans: PhaseRunPlanResolver,
  private val branchWorktrees: BranchWorktreeResources,
) {
  public fun runInvocation(request: PhaseRunRequest): PhaseRunResult {
    val result = run(request)
    if (result !is PhaseRunResult.Completed) return result
    val holder = BranchWorktreeHolder(BranchWorktreeHolderKind.PHASE, request.definitionId)
    return when (val released = branchWorktrees.release(request.repoRoot, holder)) {
      is BranchWorktreeRelease.Kept ->
        result.copy(targetReport = result.targetReport + "Kept worktree ${released.path}: ${released.detail}.")
      else -> result
    }
  }

  public fun run(request: PhaseRunRequest): PhaseRunResult {
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
    val branch = plans.currentBranch(request)?.branch
    val selector = request.intake?.trim()?.takeIf(String::isNotEmpty) ?: branch?.let { "branch $it" } ?: "no target"
    if (request.recoverStaleOwner != null && request.intake.isNullOrBlank()) {
      return monitorBlocked(
        request,
        invocationId,
        "Stale owner recovery needs an explicit target.",
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
          earlySetupReport(selector, preparation.identity, preparation.settlement),
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
          earlySetupReport(selector, preparation.identity, preparation.settlement),
          preparation.attempt,
        )
    }
  }

  public fun runForGoal(
    request: PhaseRunRequest,
    parentWorkflowId: String,
    expectedBranch: String,
  ): PhaseRunResult = runInternal(request, parentWorkflowId, parentWorkflowId, expectedBranch)

  public fun runForGoalQuality(
    request: PhaseRunRequest,
    parentWorkflowId: String,
    invocationId: String,
    expectedBranch: String,
    acceptedExecutionPlan: String,
  ): PhaseRunResult {
    validateQualityRoutingReference(request, parentWorkflowId)
    if (request.goalQualityInvocationId != invocationId) {
      plans.invalidQualityPlan("The phase-run invocation does not match the accepted quality invocation.")
    }
    val progressAvailable = request.goalQualityPhaseProgress != null && request.persistGoalQualityPhaseProgress != null
    val latestAvailable = request.latestGoalQualityPhaseProgress != null && acceptedExecutionPlan.isNotBlank()
    if (!progressAvailable || !latestAvailable) {
      plans.invalidQualityPlan("Phase-run evidence rejected at 166.")
    }
    val retained = request.goalQualityPhaseProgress
    val acceptedAgentId = requireNotNull(request.acceptedGoalQualityAgentId)
    val plan =
      plans.validateGoalQualityExecutionPlan(
        acceptedExecutionPlan,
        request.definitionId,
        acceptedAgentId,
        request.workflowAgentAssignments?.takeIf { request.agentAssignmentsWorkflowId != null },
      )
    if (plans.requiresTerminalQualitySourceEvidence(plan) &&
      request.terminalGoalQualityContentFingerprint == null
    ) {
      plans.invalidQualityPlan("Phase-run evidence rejected at 178.")
    }
    retained.let {
      plans.validateNestedProgressKeys(it)
      plans.validateRetainedRunningPhases(it, plan, "$parentWorkflowId:$invocationId")
    }
    reconcileGoalRepairOutputs(request, invocationId, retained)
    retainedValidationResult(request, parentWorkflowId, invocationId, plan, retained)?.let { return it }
    val result =
      runInternal(
        request.copy(acceptedGoalQualityPlanText = acceptedExecutionPlan),
        invocationId,
        parentWorkflowId,
        expectedBranch,
      )
    reconcileGoalRepairOutputs(request, invocationId, request.goalQualityPhaseProgress)
    persistQualityTerminal(request, invocationId, result)
    return result
  }

  private fun retainedValidationResult(
    request: PhaseRunRequest,
    parentWorkflowId: String,
    invocationId: String,
    plan: ResolvedPhaseExecutionPlan,
    retained: GoalQualityPhaseProgress,
  ): PhaseRunResult? {
    if (retained.terminalCompleted) {
      if (request.terminalGoalQualityContentFingerprint != null) {
        requireCurrentQualityBoundary(request, parentWorkflowId, invocationId, plan, retained)
      }
      return completedQualityResult(invocationId, retained)
    }
    if (retained.terminalBlockedStep != null) {
      if (request.terminalGoalQualityContentFingerprint != null) {
        requireCurrentQualityBoundary(request, parentWorkflowId, invocationId, plan, retained)
      }
      return blockedQualityResult(invocationId, retained)
    }
    val acceptedBoundary = plans.hasAcceptedValidationBoundary(retained, plan)
    if (request.terminalGoalQualityContentFingerprint != null && acceptedBoundary) {
      val blocked = retained.phaseRecords.values.singleOrNull { it.status == WorkflowStepStatus.BLOCKED }
      if (blocked != null) {
        val sourceFingerprint =
          requireCurrentQualityBoundary(
            request,
            parentWorkflowId,
            invocationId,
            plan,
            retained,
          )
        val reason =
          blocked.blockedReason?.takeIf(
            String::isNotBlank,
          ) ?: plans.invalidQualityPlan("Retained quality phase block has no reason.")
        val terminal = retained.copy(terminalBlockedStep = blocked.phaseId, terminalBlockedReason = reason)
        if (!requireNotNull(request.persistGoalQualityPhaseProgress)(terminal, sourceFingerprint)) {
          throw SkillBillRuntimeException(
            WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
            "Could not persist recovered quality phase block for '$invocationId'.",
          )
        }
        return blockedQualityResult(invocationId, terminal)
      }
    }
    return null
  }

  private fun persistQualityTerminal(
    request: PhaseRunRequest,
    invocationId: String,
    result: PhaseRunResult,
  ) {
    val sink = requireNotNull(request.persistGoalQualityPhaseProgress)
    val latest = requireNotNull(request.latestGoalQualityPhaseProgress).invoke()
    if (result is PhaseRunResult.Blocked && latest.phaseRecords[result.stepId] == null) return
    val terminal =
      when (result) {
        is PhaseRunResult.Completed -> latest.copy(terminalCompleted = true)
        is PhaseRunResult.Blocked ->
          if (FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(result.reason)) {
            null
          } else {
            latest.copy(
              terminalBlockedStep = result.stepId,
              terminalBlockedReason = result.reason,
            )
          }
      }
    val terminalFingerprint =
      terminal?.takeIf { request.terminalGoalQualityContentFingerprint != null }
        ?.let { terminalQualityContentFingerprint(request, latest, result) }
    if (terminal != null && !sink(terminal, terminalFingerprint)) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "Could not persist terminal '${request.definitionId}' progress for '$invocationId'.",
      )
    }
  }

  private fun terminalQualityContentFingerprint(
    request: PhaseRunRequest,
    latest: GoalQualityPhaseProgress,
    result: PhaseRunResult,
  ): String {
    val phaseId =
      when (result) {
        is PhaseRunResult.Completed ->
          result.completedStepIds.lastOrNull() ?: plans.invalidQualityPlan(
            "Completed quality phase has no completed step.",
          )
        is PhaseRunResult.Blocked -> result.stepId
      }
    val attempt =
      latest.phaseRecords[phaseId]?.attemptCount ?: plans.invalidQualityPlan(
        "Missing phase record for $phaseId: ${(result as? PhaseRunResult.Blocked)?.reason}",
      )
    val event =
      when (result) {
        is PhaseRunResult.Completed ->
          latest.phaseLedger.filter {
            it.phaseId == phaseId && it.attemptCount == attempt &&
              it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE
          }.maxByOrNull { it.sequenceNumber }
        is PhaseRunResult.Blocked -> latest.acceptedBlockedValidationEvent(phaseId, attempt)
      } ?: plans.invalidQualityPlan("Quality phase has no accepted terminal ledger event.")
    return matchingSourceFingerprint(latest, SourceMatch(phaseId, attempt, event.sequenceNumber, null), request)
      ?: plans.invalidQualityPlan(
        "Missing source observation for $phaseId#$attempt " +
          "event ${event.sequenceNumber}, occurrence ${request.goalQualityValidationOccurrence}: " +
          "${latest.acceptedSourceObservations}.",
      )
  }

  private fun reconcileGoalRepairOutputs(
    request: PhaseRunRequest,
    invocationId: String,
    progress: GoalQualityPhaseProgress?,
  ) {
    val sink = request.acceptedGoalRepairOutput ?: return
    progress?.acceptedOutputs?.forEach { output ->
      sink(output.phaseId, invocationId, output.occurrence, output.output)
    }
  }

  private fun requireCurrentQualityBoundary(
    request: PhaseRunRequest,
    parentWorkflowId: String,
    invocationId: String,
    plan: ResolvedPhaseExecutionPlan,
    progress: GoalQualityPhaseProgress,
  ): String {
    val currentFingerprint =
      request.terminalGoalQualityContentFingerprint?.invoke() ?: plans.invalidQualityPlan(
        "Retained quality phase has no terminal source observer.",
      )
    val repoRoot = request.repoRoot
    if (!plans.acceptedValidationSourceIsCurrent(
        GoalQualityValidationSourceRequest(
          progress,
          plan,
          "$parentWorkflowId:$invocationId",
          repoRoot,
          currentFingerprint,
          validationOccurrence = request.goalQualityValidationOccurrence,
        ),
      )
    ) {
      plans.invalidQualityPlan("Retained quality phase source evidence is stale or inconsistent.")
    }
    val terminalRecord =
      progress.phaseRecords.values.singleOrNull { it.status == WorkflowStepStatus.BLOCKED }
        ?: plan.selectedStepIds.lastOrNull()?.let(progress.phaseRecords::get)
        ?: plans.invalidQualityPlan("Retained quality phase has no terminal phase record.")
    val event =
      if (terminalRecord.status == WorkflowStepStatus.BLOCKED) {
        progress.acceptedBlockedValidationEvent(terminalRecord.phaseId, terminalRecord.attemptCount)
      } else {
        progress.phaseLedger.filter {
          it.phaseId == terminalRecord.phaseId && it.attemptCount == terminalRecord.attemptCount &&
            it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE
        }.maxByOrNull { it.sequenceNumber }
      } ?: plans.invalidQualityPlan("Retained quality phase has no accepted terminal event.")
    return matchingSourceFingerprint(
      progress,
      SourceMatch(terminalRecord.phaseId, terminalRecord.attemptCount, event.sequenceNumber, currentFingerprint),
      request,
    ) ?: plans.invalidQualityPlan("Retained quality phase has no matching source observation.")
  }

  private fun matchingSourceFingerprint(
    progress: GoalQualityPhaseProgress,
    match: SourceMatch,
    request: PhaseRunRequest,
  ): String? =
    progress.acceptedSourceObservations.singleOrNull { observation ->
      sourceIdentityMatches(observation, match, request) && fingerprintMatches(observation, match)
    }?.contentFingerprint

  private fun sourceIdentityMatches(
    observation: GoalQualityAcceptedSourceObservation,
    match: SourceMatch,
    request: PhaseRunRequest,
  ): Boolean {
    val sameStep = observation.phaseId == match.phaseId && observation.attempt == match.attempt
    val buildBoundary = request.definitionId == goalBuildDefinitionId
    val occurrenceMatches =
      buildBoundary || observation.validationOccurrence == request.goalQualityValidationOccurrence
    return sameStep && observation.completionSequenceNumber == match.sequenceNumber && occurrenceMatches
  }

  private fun fingerprintMatches(
    observation: GoalQualityAcceptedSourceObservation,
    match: SourceMatch,
  ): Boolean = match.requiredFingerprint == null || observation.contentFingerprint == match.requiredFingerprint

  private fun completedQualityResult(
    invocationId: String,
    progress: GoalQualityPhaseProgress,
  ): PhaseRunResult.Completed {
    val completedSteps =
      progress.phaseLedger
        .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE }
        .map { it.phaseId }
    val outputs = progress.acceptedOutputs.map { it.output }
    return PhaseRunResult.Completed(
      invocationId = invocationId,
      completedStepIds = completedSteps,
      reviewResult = null,
      value = outputs.lastOrNull(),
      completedOutputs = outputs,
      acceptedOutput = outputs.lastOrNull(),
      identifiedCompletedOutputs =
        progress.acceptedOutputs.map {
          PhaseRunCompletedOutput(it.phaseId, it.occurrence, it.output)
        },
    )
  }

  private fun blockedQualityResult(
    invocationId: String,
    progress: GoalQualityPhaseProgress,
  ): PhaseRunResult.Blocked {
    val completedSteps =
      progress.phaseLedger
        .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE }
        .map { it.phaseId }
    val outputs = progress.acceptedOutputs.map { it.output }
    return PhaseRunResult.Blocked(
      invocationId = invocationId,
      completedStepIds = completedSteps,
      reviewResult = null,
      stepId = requireNotNull(progress.terminalBlockedStep),
      reason = requireNotNull(progress.terminalBlockedReason),
      completedOutputs = outputs,
      identifiedCompletedOutputs =
        progress.acceptedOutputs.map {
          PhaseRunCompletedOutput(it.phaseId, it.occurrence, it.output)
        },
    )
  }

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
    val publisher = statusPublisher(request, invocationId, publicPhaseId, monitor)
    val statusRequest =
      request.copy(
        openCodeIdentity = publisher.openCodeIdentity ?: request.openCodeIdentity,
        modelObserver = publisher.modelObserver(request.modelObserver),
        eventSink = statusPublisherFactory.compose(request.eventSink::emit, publisher),
        activityOutput = { stepName, activity ->
          publisher.publishActivity(stepName, activity)
          request.activityOutput?.invoke(stepName, activity)
        },
      )
    if (SkeletonDefinition.runHarnessOf(publicPhaseId) == PhaseRunHarness.DEVICE_QA) {
      publisher.recordQaOutcome(StandaloneQaOutcome.ATTEMPTED)
      publisher.publishActivity(publicPhaseId, "QA started")
    }
    monitor?.cleanup?.onSettled { settlement ->
      catchingNonCancellation { publisher.recordMonitorCleanup(settlement.toStatusCleanup()) }
        .onFailure { failure ->
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "standalone-phase.monitor-cleanup-status " +
              "failure=${failure.failureCodeLabel() ?: failure::class.simpleName}",
            failure,
          )
        }
    }
    val outcome =
      try {
        catchingNonCancellation { body(statusRequest) }
      } catch (cancelled: CancellationException) {
        Result.failure(cancelled)
      } catch (interrupted: InterruptedException) {
        Result.failure(interrupted)
      }
    val settlement = monitor?.cleanup?.settle(primaryOutcomeOf(outcome))
    return outcome
      .map { result ->
        monitor?.let { result.withTargetReport(it.context, settlement, it.ledger.followedHeadShas) } ?: result
      }
      .onSuccess(publisher::settle)
      .onFailure(publisher::settleFailure)
      .getOrElse { failure ->
        if (failure.isCooperativeCancellation()) throw failure
        throw monitor?.let { monitorRunFailure(failure, it.context, settlement, it.ledger.followedHeadShas) } ?: failure
      }
  }

  private fun statusPublisher(
    request: PhaseRunRequest,
    invocationId: String,
    publicPhaseId: String,
    monitor: MonitorPreparation.Prepared?,
  ): StandalonePhaseStatusEventSink {
    val launch =
      StandalonePhaseLaunch(
        request.invokedAgentId,
        request.launchArguments,
        acceptedIdentity = request.openCodeIdentity,
      )
    return monitor?.let {
      statusPublisherFactory.forMonitor(
        MonitorAttemptTarget(
          it.context.target,
          it.context.callerRoot,
          it.context.primaryRoot,
          it.context.worktree.path,
        ),
        publicPhaseId,
        invocationId,
        launch,
      )
    } ?: statusPublisherFactory.forPhase(
      request.repoRoot,
      publicPhaseId,
      invocationId,
      launch,
    )
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
        StandalonePhaseLaunch(request.invokedAgentId, request.launchArguments),
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
    val setup = phaseRunSetup(request, invocationId, workflowId, standalone)
    val branch = setup.branch
    val executionPlan = setup.executionPlan
    val facts = setup.facts
    if (expectedBranch != null && branch?.branch != expectedBranch) {
      return PhaseRunResult.Blocked(
        invocationId,
        emptyList(),
        null,
        executionPlan.selectedEntryStepIds.first(),
        "Goal finalization requires branch '$expectedBranch'; current branch is '${branch?.branch.orEmpty()}'.",
      )
    }
    val records = inMemoryRecords(request, workflowId, branch, standalone, invocationId)
    val state = stateFactory.create(facts, executionPlan, records, invocationId)
    val report = runLoopEntry.run(runLoopEntry.context(facts, state))
    return resultOf(report, state, records)
  }

  private fun phaseRunSetup(
    request: PhaseRunRequest,
    invocationId: String,
    workflowId: String,
    standalone: MonitorPreparation.Prepared?,
  ): PhaseRunSetup {
    validateQualityRoutingReference(request, workflowId)
    val acceptedExecutionPlan = request.acceptedGoalQualityPlanText
    val definition = SkeletonDefinition.byId(request.definitionId)
    if (definition.runStateKind != SkeletonRunStateKind.IN_MEMORY) {
      throw InMemorySkeletonDefinitionRequiredError(definition.id)
    }
    val branch =
      standalone
        ?.context
        ?.let { FeatureTaskRuntimeResolvedBranch(branch = it.worktree.branch, baseBranch = it.target.base.ref) }
        ?: plans.currentBranch(request)
    val intake = intakeResolver.resolve(definition, request, branch?.branch)
    val liveChecksInvocation = invocationId.takeIf { standaloneValidation(request, definition, workflowId) }
    val resolutionFacts =
      InMemoryPhaseRunFacts(
        request,
        definition,
        intake,
        workflowId,
        standalone?.context,
        invocationId,
        liveChecksInvocation,
      )
    val executionPlan =
      acceptedExecutionPlan?.let { encoded ->
        plans.validateGoalQualityExecutionPlan(
          encoded,
          request.definitionId,
          requireNotNull(request.acceptedGoalQualityAgentId),
          request.workflowAgentAssignments?.takeIf { request.agentAssignmentsWorkflowId != null },
        )
      } ?: plans.resolveExecutionPlan(request, workflowId, branch, resolutionFacts)
    val facts =
      InMemoryPhaseRunFacts(
        request.copy(acceptedGoalQualityExecutionPlan = executionPlan.takeIf { acceptedExecutionPlan != null }),
        definition,
        intake,
        workflowId,
        standalone?.context,
        invocationId,
        liveChecksInvocation,
      )
    plans.verifyQualityPlanLaunches(request, facts, executionPlan, diagnostics)
    return PhaseRunSetup(branch, executionPlan, facts)
  }

  private fun validateQualityRoutingReference(
    request: PhaseRunRequest,
    workflowId: String,
  ) {
    val hasReference = request.agentAssignmentsWorkflowId != null || request.agentAssignmentsFingerprint != null
    if (!hasReference) {
      validateLegacyRoutingCompatibility(request, workflowId)
      return
    }
    if (!frozenRoutingMatches(request, workflowId)) {
      invalidQualityRoutingReference()
    }
  }

  private fun validateLegacyRoutingCompatibility(
    request: PhaseRunRequest,
    workflowId: String,
  ) {
    val assignments = request.workflowAgentAssignments
    if (assignments == null && request.workflowAgentAssignmentsFingerprint == null) {
      return
    }
    val legacyCompatible =
      assignments != null &&
        assignments.policyMode == WorkflowAgentAssignmentPolicyMode.LEGACY &&
        assignments.admissionWorkflowId == workflowId &&
        !request.workflowAgentAssignmentsFingerprint.isNullOrBlank()
    if (!legacyCompatible) {
      invalidQualityRoutingReference()
    }
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=workflow_agent_assignment phase=${request.definitionId.take(MAX_DIAGNOSTIC_ID)} " +
        "reason=legacy_record_use outcome=compatible",
    )
  }

  private fun frozenRoutingMatches(
    request: PhaseRunRequest,
    workflowId: String,
  ): Boolean {
    val assignments = request.workflowAgentAssignments ?: return false
    return !request.agentAssignmentsWorkflowId.isNullOrBlank() &&
      request.agentAssignmentsWorkflowId == workflowId &&
      !request.agentAssignmentsFingerprint.isNullOrBlank() &&
      assignments.admissionWorkflowId == request.agentAssignmentsWorkflowId &&
      assignments.policyMode == WorkflowAgentAssignmentPolicyMode.FROZEN &&
      request.workflowAgentAssignmentsFingerprint == request.agentAssignmentsFingerprint
  }

  private fun invalidQualityRoutingReference(): Nothing =
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
      "The goal-quality routing reference does not match its accepted parent artifact.",
    )

  private fun inMemoryRecords(
    request: PhaseRunRequest,
    workflowId: String,
    branch: FeatureTaskRuntimeResolvedBranch?,
    standalone: MonitorPreparation.Prepared?,
    invocationId: String,
  ): InMemoryPhaseRunRecords =
    InMemoryPhaseRunRecords(
      clock,
      branch,
      standalone?.ledger,
      request.recordedGoalQaOutcome?.let { recorded ->
        QaRepairState.resumedAfter(
          workflowId,
          recorded.qaFixAttempts,
          recorded.testedTreeIdentity,
          recorded.notFixedFindings.map { finding ->
            QaOpenFinding(
              id = finding.id,
              affectedCriteria = emptyList(),
              receiptRefs = listOfNotNull(finding.reportPath),
            )
          },
        )
      },
      request.acceptedGoalRepairOutput?.let { sink ->
        { phase, attempt, output -> sink(phase, invocationId, attempt, output) }
      },
      request.goalQualityPhaseProgress,
      request.persistGoalQualityPhaseProgress,
      workflowId,
      qaSourceWitness = request.acceptedGoalQualityQaSourceWitness,
      qualitySourceObservation =
        if (request.acceptedGoalQualitySourceFingerprint != null && request.goalQualityPhaseProgress != null) {
          { phaseId, attempt ->
            GoalQualityAcceptedSourceObservation(
              phaseId,
              attempt,
              request.acceptedGoalQualitySourceFingerprint.invoke(),
              validationOccurrence = request.goalQualityValidationOccurrence,
            )
          }
        } else {
          null
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
          value = report.completedPhaseIds.lastOrNull()?.let { id -> records.acceptedOutputs[id] },
          completedOutputs = completedOutputs(report.completedPhaseIds, records),
          acceptedOutput = report.completedPhaseIds.lastOrNull()?.let { records.acceptedOutputs[it] },
          identifiedCompletedOutputs = records.identifiedCompletedOutputs(report.completedPhaseIds),
          reviewRunId = state.reviewRunId,
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
      identifiedCompletedOutputs = records.identifiedCompletedOutputs(completedStepIds),
    )

  private fun completedOutputs(
    stepIds: List<String>,
    records: InMemoryPhaseRunRecords,
  ): List<String> {
    return stepIds.mapNotNull { id -> records.loadPhaseRecords("")[id]?.outputArtifact?.takeIf(String::isNotBlank) }
  }

  private fun standaloneValidation(
    request: PhaseRunRequest,
    definition: SkeletonDefinition,
    workflowId: String,
  ): Boolean =
    definition.reportsLiveChecks &&
      workflowId.isEmpty() &&
      request.goalQualityPhaseProgress == null &&
      request.acceptedGoalQualityPlanText == null &&
      request.goalQualityInvocationId == null
}

private data class PhaseRunSetup(
  val branch: FeatureTaskRuntimeResolvedBranch?,
  val executionPlan: ResolvedPhaseExecutionPlan,
  val facts: InMemoryPhaseRunFacts,
)

private fun newInvocationId(): String = "$INVOCATION_ID_PREFIX${UUID.randomUUID()}"

private const val INVOCATION_ID_PREFIX = "phr-"
private const val MAX_DIAGNOSTIC_ID = 128

private data class SourceMatch(
  val phaseId: String,
  val attempt: Int,
  val sequenceNumber: Int,
  val requiredFingerprint: String?,
)
