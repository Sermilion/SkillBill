package skillbill.engine.featuretask.slot.state

import skillbill.application.telemetry.model.PrDescriptionGeneratedRequest
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseRepositoryObservations
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAcceptedStepCallTarget
import skillbill.engine.featuretask.slot.attempt.PhaseStepCall
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import java.nio.file.Path

/** Step binding for one accepted step; strategies read launch facts through [launchState] only. */
internal interface PhaseStepBinding {
  /** The phase id this binding was issued for; attempt authorization rejects other step ids. */
  val acceptedPhaseId: String

  /**
   * The strategy the execution plan selected for [acceptedPhaseId]. Step calls take their prompt,
   * policy, and strategy id from it, so a wrapper that delegates execution keeps its own identity.
   */
  val acceptedOwner: PhaseStrategy

  val launchState: PhaseLaunchState

  fun nextStepIteration(): Int

  fun resolvedBranch(): FeatureTaskRuntimeResolvedBranch?

  fun completedStepEnvelope(stepId: String): FeatureTaskRuntimeWorkflowArtifactMap?

  fun completedStepPayload(stepId: String): String?

  val resolvedBranchName: String?

  fun stepCompleted(iteration: Int)

  fun isStepCompleted(stepId: String): Boolean

  fun isEvidenceInvalidated(stepId: String): Boolean

  fun requireAcceptedAttempt(
    run: PhaseRun,
    call: PhaseStepCall,
  )

  fun requireAcceptedStep(
    run: PhaseRun,
    strategyId: String,
  )

  fun finishStepExecution()
}

/** Accepted-step agent launch and branch guard without review-only persistence. */
internal interface PhaseAcceptedStepExecution :
  PhaseStepBinding,
  PhaseAcceptedStepCallTarget {
  /**
   * Records [stepId] as completed by the runtime from [output], ahead of any launch. Throws when the completion
   * cannot persist atomically.
   */
  fun settleRuntimeAuthoredCompletion(
    stepId: String,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
  )
}

/** Agent and side-effect steps execute through the accepted attempt owner only. */
internal interface PhaseAgentStepBinding :
  PhaseAcceptedStepExecution,
  PhaseAgentExecution

/** Agent launch is available only to steps that execute an agent through the attempt owner. */
internal interface PhaseAgentExecution {
  fun runAcceptedAgentStep(
    run: PhaseRun,
    call: PhaseStepCall,
  ): PhaseOutcome
}

/** Planning attempts persist their required briefing through the accepted planning owner. */
internal interface PhasePlanningBriefingBinding : PhaseAcceptedStepExecution {
  fun recordPlanningBriefing(
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    attempt: Int,
  ): RequiredPhaseWrite
}

/** Goal planning fan-out runs units through its owner. */
internal interface PhaseQualityGateStepBinding : PhaseAcceptedStepExecution {
  fun runSelectedQualityGate(
    run: PhaseRun,
    call: PhaseStepCall,
  ): PhaseOutcome
}

internal interface PhaseCommitStepBinding : PhaseAcceptedStepExecution {
  fun runCommitPush(run: PhaseRun): PhaseOutcome
}

internal interface PhaseMonitorStepBinding : PhaseAcceptedStepExecution {
  fun runMonitor(
    run: PhaseRun,
    observation: PhaseCiObservation,
  ): PhaseOutcome
}

/** Pull request CI observation the monitor step drives; the selected monitor strategy supplies it. */
internal interface PhaseCiObservation {
  /** Watches the pull request open for [branch] until its checks settle, time out, or cannot be read. */
  fun watch(
    repoRoot: Path,
    branch: String,
  ): PullRequestCiOutcome

  /** Keeps the failing [checks] so the fix step for [issueKey] can name them. */
  fun recordFailingChecks(
    issueKey: String,
    checks: List<PullRequestCheck>,
  )
}

internal sealed interface PullRequestCiOutcome {
  data object Passed : PullRequestCiOutcome

  data object NoCiConfigured : PullRequestCiOutcome

  data object NoPullRequest : PullRequestCiOutcome

  data class Failed(val failingChecks: List<PullRequestCheck>) : PullRequestCiOutcome

  data class Blocked(val reason: String) : PullRequestCiOutcome

  data class Unavailable(val reason: String) : PullRequestCiOutcome
}

internal interface PhasePullRequestStepBinding : PhaseAgentStepBinding {
  fun pullRequestContext(): PhasePullRequestContext
}

internal interface PhasePlanningStepBinding : PhaseAgentStepBinding {
  fun fanOut(stepId: String): PhaseRunFanOut

  fun authorizeFanOutWave(run: PhaseRun)

  fun releaseFanOutWave(run: PhaseRun)
}

/** Planning fan-out unit execution; not a general run-loop step binding. */
internal interface PhasePlanningUnitBinding : PhaseAgentStepBinding

/**
 * Review and remediation steps combine accepted-step execution with review-owned persistence.
 * This type does not extend [PhaseAgentStepBinding], so non-review consumers cannot treat a
 * review binding as an ordinary agent marker binding.
 */
internal interface PhaseReviewStepBinding :
  PhaseAcceptedStepExecution,
  PhaseAgentExecution,
  PhaseReviewPassState,
  PhaseReviewSettlementState,
  PhaseReviewFindingObservations,
  PhaseReviewGenerationState {
  fun reviewExecutionContext(): PhaseReviewExecutionContext

  fun startReviewStep(
    run: PhaseRun,
    iteration: Int,
  ): PhaseOutcome?

  fun blockRequiredReviewWrite(rejection: RequiredPhaseWrite.Rejected): PhaseOutcome
}

/** verify_findings reads and writes only the finding-verification ledger for its step. */
internal interface PhaseVerifyFindingsStepBinding :
  PhaseAgentStepBinding,
  PhaseFindingVerificationState

/** implement_fix records repair receipts and reads review verdict handoff facts for its step. */
internal interface PhaseImplementFixStepBinding :
  PhaseAgentStepBinding,
  PhaseRepairReceiptState

internal data class PhasePullRequestContext(
  val request: FeatureTaskRuntimeRunFacts,
  val gitOperations: PhaseRepositoryObservations,
  val diagnostics: RuntimeDiagnostics,
  val prDescriptionGenerated: (PrDescriptionGeneratedRequest) -> Unit,
  val transitions: FeatureTaskRuntimeTransitionDeclaration,
  private val branch: FeatureTaskRuntimeResolvedBranch?,
) {
  fun resolvedBranch(): FeatureTaskRuntimeResolvedBranch? = branch
}
