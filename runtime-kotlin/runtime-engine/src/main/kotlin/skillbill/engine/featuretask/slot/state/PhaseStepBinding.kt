package skillbill.engine.featuretask.slot.state

import skillbill.application.telemetry.model.PrDescriptionGeneratedRequest
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.phaserun.monitor.StandaloneMonitorLedger
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.ModelDirectiveSupplements
import skillbill.engine.featuretask.slot.PhaseBoundStepHooks
import skillbill.engine.featuretask.slot.PhaseRepositoryObservations
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAcceptedStepCallTarget
import skillbill.engine.featuretask.slot.attempt.PhaseStepCall
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import java.nio.file.Path
import java.time.Instant

/** Step binding for one accepted step; strategies read launch facts through [launchState] only. */
public interface PhaseStepBinding {
  /** The phase id this binding was issued for; attempt authorization rejects other step ids. */
  public val acceptedPhaseId: String

  /**
   * The strategy the execution plan selected for [acceptedPhaseId]. Step calls take their prompt,
   * policy, and strategy id from it, so a wrapper that delegates execution keeps its own identity.
   */
  public val acceptedOwner: PhaseStrategy

  public val modelDirectiveSupplements: ModelDirectiveSupplements

  public val launchState: PhaseLaunchState

  public fun nextStepIteration(): Int

  public fun resolvedBranch(): FeatureTaskRuntimeResolvedBranch?

  public fun completedStepEnvelope(stepId: String): FeatureTaskRuntimeWorkflowArtifactMap?

  public fun completedStepPayload(stepId: String): String?

  public val resolvedBranchName: String?

  public fun stepCompleted(iteration: Int)

  public fun isStepCompleted(stepId: String): Boolean

  public fun isEvidenceInvalidated(stepId: String): Boolean

  public fun requireAcceptedAttempt(
    run: PhaseRun,
    call: PhaseStepCall,
  )

  public fun requireAcceptedStep(
    run: PhaseRun,
    strategyId: String,
  )

  public fun finishStepExecution()
}

/** Accepted-step agent launch and branch guard without review-only persistence. */
public interface PhaseAcceptedStepExecution :
  PhaseStepBinding,
  PhaseAcceptedStepCallTarget {
  /**
   * Records [stepId] as completed by the runtime from [output], ahead of any launch. Throws when the completion
   * cannot persist atomically.
   */
  public fun settleRuntimeAuthoredCompletion(
    stepId: String,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
  )

  public val boundHooks: PhaseBoundStepHooks<*>
}

/** Agent and side-effect steps execute through the accepted attempt owner only. */
public interface PhaseAgentStepBinding :
  PhaseAcceptedStepExecution,
  PhaseAgentExecution

public interface PhaseQaStepBinding : PhaseAgentStepBinding {
  public fun qaRepairState(): QaRepairState?
}

/** Agent launch is available only to steps that execute an agent through the attempt owner. */
public interface PhaseAgentExecution {
  public fun runAcceptedAgentStep(
    run: PhaseRun,
    call: PhaseStepCall,
  ): PhaseOutcome
}

/** Planning attempts persist their required briefing through the accepted planning owner. */
public interface PhasePlanningBriefingBinding : PhaseAcceptedStepExecution {
  public fun recordPlanningBriefing(
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    attempt: Int,
  ): RequiredPhaseWrite
}

/** Goal planning fan-out runs units through its owner. */
public interface PhaseQualityGateStepBinding : PhaseAcceptedStepExecution {
  public fun runSelectedQualityGate(
    run: PhaseRun,
    call: PhaseStepCall,
  ): PhaseOutcome
}

public interface PhaseCommitStepBinding : PhaseAcceptedStepExecution {
  public fun runCommitPush(run: PhaseRun): PhaseOutcome
}

public interface PhaseMonitorStepBinding :
  PhaseAcceptedStepExecution,
  PhaseAgentExecution {
  public fun publishRepair(
    run: PhaseRun,
    pullRequests: PullRequestIdentityLookup,
    conflictBaseBranch: String?,
  ): String?

  public fun runMonitor(
    run: PhaseRun,
    observation: PhaseCiObservation,
  ): PhaseOutcome
}

/** Pull request CI observation the monitor step drives; the selected monitor strategy supplies it. */
public interface PhaseCiObservation {
  /**
   * Watches the pull request open for [branch] until its checks settle or a usable no-check observation exceeds the
   * CI start timeout. Failed identity and checks reads are reported through [onInconclusive] and retried as unknown
   * for that round; they do not establish that CI has not started.
   * [onCiRunning] is invoked while checks are still pending so status can stay fresh for the whole run.
   */
  public fun watch(
    repoRoot: Path,
    branch: String,
    onCiRunning: (String, List<PullRequestCheck>) -> Unit = { _, _ -> },
    expectedHead: ExpectedCiHead = ExpectedCiHead(),
    onInconclusive: (String) -> Unit = {},
  ): PullRequestCiOutcome

  /** Keeps the failing [checks] so the fix step for [issueKey] can name them. */
  public fun recordFailingChecks(
    issueKey: String,
    checks: List<PullRequestCheck>,
  )

  /** Keeps [baseBranch] so the fix step for [issueKey] can merge it and resolve conflicts. */
  public fun recordMergeConflict(
    issueKey: String,
    baseBranch: String,
  )
}

/** The accepted standalone monitor and monitor-repair steps; both act only on the invocation's own ledger. */
public interface PhaseStandaloneMonitorStepBinding :
  PhaseAcceptedStepExecution,
  PhaseAgentExecution {
  public fun runStandaloneMonitor(
    run: PhaseRun,
    operations: PhaseStandaloneMonitorOperations,
  ): PhaseOutcome

  /** The evidence brief for the repair the monitor started, or an empty string when none is pending. */
  public fun standaloneRepairBrief(): String

  /** The reason the repair cannot launch against the current pull request and worktree, or null when it can. */
  public fun guardStandaloneRepairLaunch(
    run: PhaseRun,
    operations: PhaseStandaloneMonitorOperations,
  ): String?

  /**
   * The reason the settled repair cannot be published, or null when it was published or nothing was pending. A repair
   * the agent did not report as fixed is never completed or published.
   */
  public fun publishStandaloneRepair(
    run: PhaseRun,
    operations: PhaseStandaloneMonitorOperations,
    reportedFixed: Boolean,
  ): String?
}

/** The accepted standalone publication step; its first visit changes nothing. */
public interface PhaseStandalonePublicationStepBinding : PhaseAcceptedStepExecution {
  public fun runStandalonePublication(
    run: PhaseRun,
    operations: PhaseStandaloneMonitorOperations,
  ): PhaseOutcome
}

/** Identity-based observation, repair evidence and guarded publication the standalone monitor strategy supplies. */
public interface PhaseStandaloneMonitorOperations {
  /** Watches the pinned pull request until its checks settle, it merges or closes, or it cannot be read. */
  public fun observe(
    ledger: StandaloneMonitorLedger,
    onCiRunning: (String, List<PullRequestCheck>) -> Unit,
  ): PullRequestCiOutcome

  /** The repair brief for [failing] checks on the last head this invocation published. */
  public fun failureEvidence(
    ledger: StandaloneMonitorLedger,
    failing: List<PullRequestCheck>,
  ): String

  /** The repair brief for a merge conflict with the pinned base commit. */
  public fun conflictEvidence(ledger: StandaloneMonitorLedger): String

  /** The reason the pending repair cannot launch, or null when the target and worktree are unchanged. */
  public fun guardRepairLaunch(
    ledger: StandaloneMonitorLedger,
    worktree: StandaloneRepairWorktree,
  ): String?

  /** Publishes the completed pending repair, or reports that nothing is pending or why publication blocked. */
  public fun publish(
    ledger: StandaloneMonitorLedger,
    worktree: StandaloneRepairWorktree,
  ): StandalonePublication
}

/**
 * The owned worktree of this invocation's repair, bound to that path. It can only read HEAD, test ancestry and commit
 * the repair's own changes; it cannot push, fetch, reset, check out, tag or address another checkout.
 */
public interface StandaloneRepairWorktree {
  public fun headSha(): String?

  public fun descendsFrom(
    ancestorSha: String,
    descendantSha: String,
  ): Boolean

  /** The reason the repair's changes could not be committed, or null when committed or nothing was pending. */
  public fun commitPending(subject: String): String?
}

public sealed interface StandalonePublication {
  public data object NothingPending : StandalonePublication

  public data class Published(val commitSha: String) : StandalonePublication

  public data class PullRequestEdited(val headSha: String) : StandalonePublication

  public data class Blocked(val reason: String) : StandalonePublication
}

public data class ExpectedCiHead(
  val sha: String? = null,
  val refreshAfter: Instant? = null,
)

public sealed interface PullRequestCiOutcome {
  public data class Passed(val headSha: String) : PullRequestCiOutcome

  public data object Merged : PullRequestCiOutcome

  public data object NoCiConfigured : PullRequestCiOutcome

  public data object NoPullRequest : PullRequestCiOutcome

  public data class Failed(
    val failingChecks: List<PullRequestCheck>,
    val headSha: String? = null,
    val editableFieldsDigest: String? = null,
  ) : PullRequestCiOutcome

  public data object Conflicted : PullRequestCiOutcome

  public data class Blocked(val reason: String) : PullRequestCiOutcome

  public data class Unavailable(val reason: String) : PullRequestCiOutcome
}

public interface PhasePullRequestStepBinding : PhaseAgentStepBinding {
  public fun pullRequestContext(): PhasePullRequestContext
}

public interface PhasePlanningAgentStepBinding :
  PhaseAgentStepBinding,
  PhasePlanningBriefingBinding

public interface PhasePlanningStepBinding : PhasePlanningAgentStepBinding {
  /**
   * Completes the accepted plan step from the spec bundle already on disk without launching an agent, recording its
   * snapshot. Subtasks that already carry their plan are recorded as settled units of [unitStepId]. Returns null
   * when the step must launch an agent to author the bundle.
   */
  public fun settleExistingBundle(
    run: PhaseRun,
    unitStepId: String,
  ): PhaseOutcome?

  public fun fanOut(stepId: String): PhaseRunFanOut

  public fun authorizeFanOutWave(run: PhaseRun)

  public fun releaseFanOutWave(run: PhaseRun)
}

/** Planning fan-out unit execution; not a general run-loop step binding. */
public interface PhasePlanningUnitBinding : PhasePlanningAgentStepBinding

/**
 * Review and remediation steps combine accepted-step execution with review-owned persistence.
 * This type does not extend [PhaseAgentStepBinding], so non-review consumers cannot treat a
 * review binding as an ordinary agent marker binding.
 */
public interface PhaseReviewStepBinding :
  PhaseAcceptedStepExecution,
  PhaseAgentExecution,
  PhaseReviewPassState,
  PhaseReviewSettlementState,
  PhaseReviewFindingObservations,
  PhaseReviewGenerationState {
  public fun reviewExecutionContext(): PhaseReviewExecutionContext

  public fun startReviewStep(
    run: PhaseRun,
    iteration: Int,
  ): PhaseOutcome?

  public fun blockRequiredReviewWrite(rejection: RequiredPhaseWrite.Rejected): PhaseOutcome
}

/** verify_findings reads and writes only the finding-verification ledger for its step. */
public interface PhaseVerifyFindingsStepBinding :
  PhaseAgentStepBinding,
  PhaseFindingVerificationState

/** implement_fix records repair receipts and reads review verdict handoff facts for its step. */
public interface PhaseImplementFixStepBinding :
  PhaseAgentStepBinding,
  PhaseRepairReceiptState

public data class PhasePullRequestContext(
  val request: FeatureTaskRuntimeRunFacts,
  val gitOperations: PhaseRepositoryObservations,
  val diagnostics: RuntimeDiagnostics,
  val prDescriptionGenerated: (PrDescriptionGeneratedRequest) -> Unit,
  val transitions: FeatureTaskRuntimeTransitionDeclaration,
  private val branch: FeatureTaskRuntimeResolvedBranch?,
) {
  public fun resolvedBranch(): FeatureTaskRuntimeResolvedBranch? = branch
}
