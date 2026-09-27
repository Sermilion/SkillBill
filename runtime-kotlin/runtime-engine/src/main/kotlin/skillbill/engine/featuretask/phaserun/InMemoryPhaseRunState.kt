package skillbill.engine.featuretask.phaserun

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.parallel.runner.ParallelCodeReviewRunnerResultAssembly
import skillbill.application.telemetry.lifecycle.LifecycleTelemetryService
import skillbill.application.telemetry.model.QualityCheckFinishedRequest
import skillbill.application.telemetry.model.QualityCheckStartedRequest
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys
import skillbill.engine.featuretask.lifecycle.branch.FeatureTaskRuntimeBranchSetupOutcome
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.strategySelectionFacts
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.runloop.state.RunLoopPhaseStepState
import skillbill.engine.featuretask.slot.PhaseLaunchObservation
import skillbill.engine.featuretask.slot.PhaseRunState
import skillbill.engine.featuretask.slot.PhaseSettledEnvelopeRead
import skillbill.engine.featuretask.slot.PhaseStepState
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLoop
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptScope
import skillbill.engine.featuretask.slot.state.PhaseRunCheckpoints
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunSettlements
import skillbill.error.shellcontent.MissingValidationGateError
import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.agentrun.model.AgentRunWorktreeEditObserver
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ParallelReviewLaneRunResult
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import java.time.Instant

internal class InMemoryPhaseRunState(
  private val facts: InMemoryPhaseRunFacts,
  override val progress: FeatureTaskRuntimeRunState,
  override val records: PhaseRunRecords,
  override val telemetry: FeatureTaskRuntimeRunObservability,
  val invocationId: String,
  private val entry: PhaseRunEntry,
) : PhaseRunState {
  override val session: FeatureTaskRuntimeRunLoopSession =
    FeatureTaskRuntimeRunLoopSession(operatorBlockRetry = null, initialPendingReentry = null)
  override val goal: PhaseRunGoal = InMemoryPhaseRunGoal
  override val settlements: PhaseRunSettlements = InMemoryPhaseRunSettlements
  override val checkpoints: PhaseRunCheckpoints = InMemoryPhaseRunCheckpoints
  override val specSource: SpecSource = facts.request.specSource
  override val transitions: FeatureTaskRuntimeTransitionDeclaration = progress.transitions
  override val attemptLoop: PhaseAttemptLoop =
    PhaseAttemptLoop(entry.outputValidator, entry.phaseGates, entry.clock, entry.diagnostics)

  var reviewResult: ParallelCodeReviewResult? = null
    private set

  private var qualityCheck: QualityCheckSession? = null

  private var reviewTarget: ReviewTarget? = null

  override fun strategyFor(stepId: String): PhaseStrategy =
    entry.strategies.strategyFor(stepId, strategySelectionFacts(facts))

  override fun selectedOwnerOf(stepId: String): PhaseStrategy? =
    entry.strategies.selectedOwnerOf(stepId, strategySelectionFacts(facts))

  override fun unselectedStepIds(): Set<String> = entry.strategies.unselectedStepIds(strategySelectionFacts(facts))

  override fun step(run: PhaseRun): PhaseStepState = RunLoopPhaseStepState(PhaseAttemptScope(run.request, this), run)

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
    val assembly = entry.reviewResultAssembly
    // The review_runs record is required, so a failed write fails the run; only the stage telemetry is best-effort.
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
      entry.diagnostics.warning("Phase run $invocationId could not report review run $reviewRunId.", error)
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
        entry.lifecycleTelemetry.qualityCheckStarted(
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
        QualityCheckSession(id, entry.clock.instant(), detectedStack, initialFailureCount)
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
      entry.lifecycleTelemetry.qualityCheckFinished(
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
          durationSeconds = (entry.clock.instant().epochSecond - started.startedAt.epochSecond).toInt(),
        ),
      )
    }
  }

  private fun <T> emitQualityCheck(
    stepName: String,
    emit: () -> T,
  ): T? =
    runCatching(emit).onFailure { error ->
      entry.diagnostics.warning("Phase run $invocationId could not report quality check of '$stepName'.", error)
    }.getOrNull()
}

private data class QualityCheckSession(
  val sessionId: String,
  val startedAt: Instant,
  val detectedStack: String,
  val initialFailureCount: Int,
)

private enum class QualityCheckResult(val wireValue: String) {
  PASS("pass"),
  FAIL("fail"),
}

private const val QUALITY_CHECK_ROUTED_SKILL = "bill-code-check"
private const val QUALITY_CHECK_SCOPE_TYPE = "working_tree"
