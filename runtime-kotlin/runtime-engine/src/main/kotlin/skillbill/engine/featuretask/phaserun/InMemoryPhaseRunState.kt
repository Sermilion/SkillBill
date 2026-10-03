package skillbill.engine.featuretask.phaserun

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.parallel.runner.ParallelCodeReviewRunnerResultAssembly
import skillbill.application.telemetry.lifecycle.LifecycleTelemetryService
import skillbill.application.telemetry.model.QualityCheckFinishedRequest
import skillbill.application.telemetry.model.QualityCheckStartedRequest
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.branch.FeatureTaskRuntimeBranchSetupOutcome
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepBindingCoordinator
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepBindings
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLoop
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptRunHost
import skillbill.engine.featuretask.slot.attempt.PhaseStepAttempts
import skillbill.engine.featuretask.slot.attempt.phaseAttemptLaunchCollaborationScope
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseLaunchObservation
import skillbill.engine.featuretask.slot.state.PhaseRunCheckpoints
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunSettlements
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.engine.featuretask.slot.state.PhaseSettledEnvelopeRead
import skillbill.error.shellcontent.MissingValidationGateError
import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.agentrun.model.AgentRunWorktreeEditObserver
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ParallelReviewLaneRunResult
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import java.time.Clock
import java.time.Instant

internal class InMemoryPhaseRunState(
  private val facts: InMemoryPhaseRunFacts,
  private val executionPlan: ResolvedPhaseExecutionPlan,
  override val progress: FeatureTaskRuntimeRunState,
  override val records: PhaseRunRecords,
  override val telemetry: FeatureTaskRuntimeRunObservability,
  val invocationId: String,
  private val strategies: PhaseStrategyLookup,
  private val reviewResultAssembly: ParallelCodeReviewRunnerResultAssembly,
  private val lifecycleTelemetry: LifecycleTelemetryService,
  override val clock: Clock,
  private val runLoopEntry: FeatureTaskRuntimeRunLoopEntry,
) : PhaseRunState {
  override val diagnostics get() = telemetry.diagnostics

  override val stepBinding: FeatureTaskRuntimeRunLoopStepBindingCoordinator =
    FeatureTaskRuntimeRunLoopStepBindingCoordinator()
  override val session: FeatureTaskRuntimeRunLoopSession =
    FeatureTaskRuntimeRunLoopSession(operatorBlockRetry = null, initialPendingReentry = null)
  override val goal: PhaseRunGoal = InMemoryPhaseRunGoal
  override val settlements: PhaseRunSettlements = InMemoryPhaseRunSettlements
  override val checkpoints: PhaseRunCheckpoints = InMemoryPhaseRunCheckpoints
  override val specSource: SpecSource = facts.request.specSource
  override val transitions: FeatureTaskRuntimeTransitionDeclaration
    get() = progress.transitions
  override val attemptLoop: PhaseStepAttempts = PhaseAttemptLoop

  var reviewResult: ParallelCodeReviewResult? = null
    private set

  private var qualityCheck: QualityCheckSession? = null

  private var reviewTarget: ReviewTarget? = null

  override fun strategyFor(stepId: String): PhaseStrategy = strategies.strategyFor(stepId, executionPlan)

  override fun runnerFor(stepId: String) = strategies.runnerFor(stepId, executionPlan)

  override fun selectedOwnerOf(stepId: String): PhaseStrategy? = strategies.selectedOwnerOf(stepId, executionPlan)

  override fun unselectedStepIds(): Set<String> = executionPlan.unselectedStepIds

  override fun step(run: PhaseRun): PhaseAcceptedStepExecution {
    require(run.request === facts)
    require(run.phaseId in executionPlan.selectedStepIds)
    require(strategyFor(run.phaseId).policyFor(run.phaseId) == run.policy)
    stepBinding.beginStepBinding(run)
    val runLoopContext = runLoopEntry.context(facts, this)
    return FeatureTaskRuntimeRunLoopStepBindings.create(
      phaseAttemptLaunchCollaborationScope(
        PhaseAttemptRunHost(
          run,
          this,
          runLoopContext.gitOperations,
          runLoopContext.decompositionPlanner,
          runLoopContext.findingVerificationBoundaryMemory,
          runLoopContext.specIntentProjectionResolver,
          runLoopContext.lifecycleTelemetry,
          runLoopContext.sharedEvidenceResolver,
          runLoopContext.diffResolver,
          runLoopContext.qualityGateCycles,
          runLoopContext.readinessGateCoordinator,
        ),
      ),
      run,
    )
  }

  override fun ensureFeatureBranch(guardPhase: String): FeatureTaskRuntimeBranchSetupOutcome =
    FeatureTaskRuntimeBranchSetupOutcome.unchanged()

  override fun settlementTarget(attempt: Int): FeatureTaskRuntimePhaseSettlementTarget? = null

  override fun launchObservation(stepName: String): PhaseLaunchObservation =
    PhaseLaunchObservation(AgentRunActivityStampSink.NONE, AgentRunWorktreeEditObserver.NONE)

  override fun recordTokenUsage(
    stepName: String,
    inputTokens: Int,
    outputTokens: Int,
  ) {
    progress.recordPhaseTokenUsage(stepName, inputTokens, outputTokens)
  }

  override fun settledEnvelope(
    stepName: String,
    target: FeatureTaskRuntimePhaseSettlementTarget,
  ): PhaseSettledEnvelopeRead = PhaseSettledEnvelopeRead.None

  override fun recordReviewRun(
    reviewRunId: String,
    result: ParallelCodeReviewResult,
    laneTelemetryRecorded: Boolean,
  ) {
    reviewResult = result
    val assembly = reviewResultAssembly
    assembly.persistReviewPassClaims(reviewRunId, result.mergeResult.findings, persistEmpty = true)
    if (laneTelemetryRecorded) return
    runCatching {
      assembly.emitReviewStageDegradations(
        reviewRunId,
        ParallelReviewLaneRunResult(
          ParallelReviewLaneOutcome(success = true, rawOutput = result.mergeResult.formattedOutput),
        ),
        verificationNonSuccess = null,
      )
    }.onFailure { error ->
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Phase run $invocationId could not report review run $reviewRunId.",
        error,
      )
    }
  }

  override fun pinnedReviewTarget(resolve: () -> ReviewTarget): ReviewTarget =
    reviewTarget ?: resolve().also { reviewTarget = it }

  override fun qualityGateAbsent(stepName: String): Unit =
    throw MissingValidationGateError(
      "The dominant platform pack has no validation_gate declaration; quality gate '$stepName' cannot run.",
    )

  override fun qualityCheckStarted(
    stepName: String,
    detectedStack: String,
    initialFailureCount: Int,
  ) {
    val sessionId =
      emitQualityCheck(stepName) {
        lifecycleTelemetry
          .qualityCheckStarted(
            QualityCheckStartedRequest(
              routedSkill = QUALITY_CHECK_ROUTED_SKILL,
              detectedStack = detectedStack,
              scopeType = QUALITY_CHECK_SCOPE_TYPE,
              initialFailureCount = initialFailureCount,
              orchestrated = false,
            ),
          ).toPayload()[LifecycleTelemetryPayloadKeys.SESSION_ID] as? String
      }
    qualityCheck =
      sessionId?.takeIf(String::isNotBlank)?.let { id ->
        QualityCheckSession(id, clock.instant(), detectedStack, initialFailureCount)
      }
  }

  override fun qualityCheckFinished(
    stepName: String,
    finalFailureCount: Int,
    failingCheckNames: List<String>,
    iterations: Int,
  ) {
    val started = qualityCheck ?: return
    qualityCheck = null
    val result = if (finalFailureCount == 0) QualityCheckResult.PASS else QualityCheckResult.FAIL
    emitQualityCheck(stepName) {
      lifecycleTelemetry.qualityCheckFinished(
        QualityCheckFinishedRequest(
          finalFailureCount = finalFailureCount,
          iterations = iterations,
          result = result.wireValue,
          sessionId = started.sessionId,
          failingCheckNames = failingCheckNames,
          unsupportedReason = "",
          orchestrated = false,
          routedSkill = QUALITY_CHECK_ROUTED_SKILL,
          detectedStack = started.detectedStack,
          scopeType = QUALITY_CHECK_SCOPE_TYPE,
          initialFailureCount = started.initialFailureCount,
          durationSeconds = (clock.instant().epochSecond - started.startedAt.epochSecond).toInt(),
        ),
      )
    }
  }

  private fun <T> emitQualityCheck(
    stepName: String,
    emit: () -> T,
  ): T? =
    runCatching(emit)
      .onFailure { error ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Phase run $invocationId could not report quality check of '$stepName'.",
          error,
        )
      }.getOrNull()
}

private data class QualityCheckSession(
  val sessionId: String,
  val startedAt: Instant,
  val detectedStack: String,
  val initialFailureCount: Int,
)

private enum class QualityCheckResult(
  val wireValue: String,
) {
  PASS("pass"),
  FAIL("fail"),
}

private const val QUALITY_CHECK_ROUTED_SKILL = "bill-code-check"
private const val QUALITY_CHECK_SCOPE_TYPE = "working_tree"
