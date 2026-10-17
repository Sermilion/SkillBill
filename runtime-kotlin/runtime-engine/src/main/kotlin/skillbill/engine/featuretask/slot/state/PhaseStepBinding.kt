package skillbill.engine.featuretask.slot.state

import skillbill.application.telemetry.model.PrDescriptionGeneratedRequest
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.phaserun.monitor.StandaloneMonitorLedger
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
  fun publishRepair(run: PhaseRun): String?

  fun runMonitor(
    run: PhaseRun,
    observation: PhaseCiObservation,
  ): PhaseOutcome
}

/** Pull request CI observation the monitor step drives; the selected monitor strategy supplies it. */
internal interface PhaseCiObservation {
  /**
   * Watches the pull request open for [branch] until its checks settle, CI fails to start, or cannot be read.
   * [onCiRunning] is invoked while checks are still pending so status can stay fresh for the whole run.
   */
  fun watch(
    repoRoot: Path,
    branch: String,
    onCiRunning: (String, List<PullRequestCheck>) -> Unit = { _, _ -> },
  ): PullRequestCiOutcome

  /** Keeps the failing [checks] so the fix step for [issueKey] can name them. */
  fun recordFailingChecks(
    issueKey: String,
    checks: List<PullRequestCheck>,
  )

  /** Keeps [baseBranch] so the fix step for [issueKey] can merge it and resolve conflicts. */
  fun recordMergeConflict(
    issueKey: String,
    baseBranch: String,
  )
}

/** The accepted standalone monitor and monitor-repair steps; both act only on the invocation's own ledger. */
internal interface PhaseStandaloneMonitorStepBinding : PhaseAcceptedStepExecution {
  fun runStandaloneMonitor(
    run: PhaseRun,
    operations: PhaseStandaloneMonitorOperations,
  ): PhaseOutcome

  /** The evidence brief for the repair the monitor started, or an empty string when none is pending. */
  fun standaloneRepairBrief(): String

  /** The reason the repair cannot launch against the current pull request and worktree, or null when it can. */
  fun guardStandaloneRepairLaunch(
    run: PhaseRun,
    operations: PhaseStandaloneMonitorOperations,
  ): String?

  /**
   * The reason the settled repair cannot be published, or null when it was published or nothing was pending. A repair
   * the agent did not report as fixed is never completed or published.
   */
  fun publishStandaloneRepair(
    run: PhaseRun,
    operations: PhaseStandaloneMonitorOperations,
    reportedFixed: Boolean,
  ): String?
}

/** The accepted standalone publication step; its first visit changes nothing. */
internal interface PhaseStandalonePublicationStepBinding : PhaseAcceptedStepExecution {
  fun runStandalonePublication(
    run: PhaseRun,
    operations: PhaseStandaloneMonitorOperations,
  ): PhaseOutcome
}

/** Identity-based observation, repair evidence and guarded publication the standalone monitor strategy supplies. */
internal interface PhaseStandaloneMonitorOperations {
  /** Watches the pinned pull request until its checks settle, it merges or closes, or it cannot be read. */
  fun observe(
    ledger: StandaloneMonitorLedger,
    onCiRunning: (String, List<PullRequestCheck>) -> Unit,
  ): PullRequestCiOutcome

  /** The repair brief for [failing] checks on the last head this invocation published. */
  fun failureEvidence(
    ledger: StandaloneMonitorLedger,
    failing: List<PullRequestCheck>,
  ): String

  /** The repair brief for a merge conflict with the pinned base commit. */
  fun conflictEvidence(ledger: StandaloneMonitorLedger): String

  /** The reason the pending repair cannot launch, or null when the target and worktree are unchanged. */
  fun guardRepairLaunch(
    ledger: StandaloneMonitorLedger,
    worktree: StandaloneRepairWorktree,
  ): String?

  /** Publishes the completed pending repair, or reports that nothing is pending or why publication blocked. */
  fun publish(
    ledger: StandaloneMonitorLedger,
    worktree: StandaloneRepairWorktree,
  ): StandalonePublication
}

/**
 * The owned worktree of this invocation's repair, bound to that path. It can only read HEAD, test ancestry and commit
 * the repair's own changes; it cannot push, fetch, reset, check out, tag or address another checkout.
 */
internal interface StandaloneRepairWorktree {
  fun headSha(): String?

  fun descendsFrom(
    ancestorSha: String,
    descendantSha: String,
  ): Boolean

  /** The reason the repair's changes could not be committed, or null when committed or nothing was pending. */
  fun commitPending(subject: String): String?
}

internal sealed interface StandalonePublication {
  data object NothingPending : StandalonePublication

  data class Published(val commitSha: String) : StandalonePublication

  data class Blocked(val reason: String) : StandalonePublication
}

internal sealed interface PullRequestCiOutcome {
  data object Passed : PullRequestCiOutcome

  data object Merged : PullRequestCiOutcome

  data object NoCiConfigured : PullRequestCiOutcome

  data object NoPullRequest : PullRequestCiOutcome

  data class Failed(val failingChecks: List<PullRequestCheck>, val headSha: String? = null) : PullRequestCiOutcome

  data object Conflicted : PullRequestCiOutcome

  data class Blocked(val reason: String) : PullRequestCiOutcome

  data class Unavailable(val reason: String) : PullRequestCiOutcome
}

internal interface PhasePullRequestStepBinding : PhaseAgentStepBinding {
  fun pullRequestContext(): PhasePullRequestContext
}

internal interface PhasePlanningStepBinding : PhaseAgentStepBinding {
  /**
   * Completes the accepted plan step from the spec bundle already on disk without launching an agent, recording its
   * snapshot. Subtasks that already carry their plan are recorded as settled units of [unitStepId]. Returns null
   * when the step must launch an agent to author the bundle.
   */
  fun settleExistingBundle(
    run: PhaseRun,
    unitStepId: String,
  ): PhaseOutcome?

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
