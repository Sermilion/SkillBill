package skillbill.engine.featuretask.model.core

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.decomposition.decompositionManifestPath
import skillbill.application.decomposition.parentSpecPath
import skillbill.config.model.CompactionSettings
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.model.execution.MonitorExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.review.ReviewInvocation
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.model.history.GoalHistoryWriterInput
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.goalreview.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationTurnRole
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import kotlin.time.Duration

public data class FeatureTaskRuntimeRunRequest(
  public override val issueKey: String,
  public override val workflowId: String,
  public val sessionId: String,
  public override val runInvariants: FeatureTaskRuntimeRunInvariants,
  public override val invokedAgentId: String,
  public override val agentAssignment: FeatureTaskRuntimeAgentAssignment = FeatureTaskRuntimeAgentAssignment(),
  public override val modelAssignment: FeatureTaskRuntimeModelAssignment = FeatureTaskRuntimeModelAssignment(),
  public override val compactionSettings: CompactionSettings = CompactionSettings.DEFAULT,
  public override val environment: Map<String, String> = emptyMap(),
  public override val repoRoot: Path,
  public override val timeout: Duration? = null,
  public override val requestedCodeReviewMode: CodeReviewExecutionMode? = null,
  public override val goalContinuation: FeatureTaskRuntimeGoalContinuationContext? = null,
  public val operatorDecision: GoalSubtaskOperatorDecision? = null,
  public override val agentAddonSelection: HydratedAgentAddonSelection = HydratedAgentAddonSelection(),
  public override val eventSink: FeatureTaskRuntimeRunEventSink = FeatureTaskRuntimeRunEventSink.NONE,
  public override val transitionsOverride: FeatureTaskRuntimeTransitionDeclaration? = null,
  public override val admittedExecution: AdmittedFeatureTaskRuntimeExecution? = null,
  public override val skeletonDefinition: SkeletonDefinition? = null,
  public override val protectedSpecSha256: String? = null,
  public override val planIntake: String? = null,
  public override val stopAfterIntake: Boolean = false,
  public val acceptanceCriterionRepair: AcceptanceCriterionRepairRunState? = null,
  public override val activityParentWorkflowId: String? = null,
) : FeatureTaskRuntimeRunFacts {
  override val qaSelection: FeatureTaskRuntimeQaSelection? = null
  override val reviewInvocation: ReviewInvocation? = null
  override val phaseInstructions: PhaseInstructions? = null
  override val monitorExecution: MonitorExecutionContext? = null
  override val qaExecution: QaExecutionContext? = null
  override val goalHistoryWriterInput: GoalHistoryWriterInput? = null
  override val goalQualityAutomaticRecovery: Boolean = false
  override val acceptedGoalQualityExecutionPlan: ResolvedPhaseExecutionPlan? = null

  init {
    require(issueKey.isNotBlank()) { "FeatureTaskRuntimeRunRequest.issueKey is required." }
    require(workflowId.isNotBlank()) { "FeatureTaskRuntimeRunRequest.workflowId is required." }
    require(invokedAgentId.isNotBlank()) {
      "FeatureTaskRuntimeRunRequest.invokedAgentId is required; it is the documented default agent."
    }
  }
}

public data class FeatureTaskRuntimeRunInput(
  public val issueKey: String,
  public val specPath: String,
  public val repoRoot: Path,
  public val explicitWorkflowId: String?,
  public val invokedAgentId: String,
  public val agentAssignment: FeatureTaskRuntimeAgentAssignment,
  public val modelAssignment: FeatureTaskRuntimeModelAssignment,
  public val compactionSettings: CompactionSettings,
  public val environment: Map<String, String>,
  public val timeout: Duration?,
  public val requestedCodeReviewMode: CodeReviewExecutionMode?,
  public val goalContinuation: FeatureTaskRuntimeGoalContinuationContext?,
  public val operatorDecision: GoalSubtaskOperatorDecision?,
  public val agentAddonSelection: HydratedAgentAddonSelection,
  public val eventSink: FeatureTaskRuntimeRunEventSink,
  public val definition: SkeletonDefinition? = null,
  internal val protectedSpecSha256: String? = null,
  internal val openArtifacts: WorkflowArtifactPatch = WorkflowArtifactPatch.EMPTY,
  public val planIntake: String? = null,
  public val qaSelection: FeatureTaskRuntimeQaSelection? = null,
  internal val inheritRecordedPlanningTimeout: Boolean = false,
  public val activityParentWorkflowId: String? = null,
)

public data class FeatureTaskRuntimeGoalContinuationContext(
  public val parentIssueKey: String,
  public val subtaskId: Int,
  public val goalBranch: String,
  public val suppressPr: Boolean,
  public val parentWorkflowId: String? = null,
  public val lastResumableStep: String? = null,
  public val codeReviewMode: CodeReviewExecutionMode? = null,
  public val validationDepth: ValidationDepth = ValidationDepth.DEFAULT,
  public val qualityGateSelection: FeatureTaskRuntimeQualityGateSelection =
    FeatureTaskRuntimeQualityGateSelection.VALIDATE,
  public val qaSelection: FeatureTaskRuntimeQaSelection? = null,
  public val subtaskName: String? = null,
  public val reviewBaseline: GoalSubtaskReviewBaseline? = null,
  public val agentAddonSelection: AgentAddonSelection =
    AgentAddonSelection(),
) {
  init {
    require(parentIssueKey.isNotBlank()) { "parentIssueKey is required." }
    require(subtaskId > 0) { "subtaskId must be positive." }
    require(goalBranch.isNotBlank()) { "goalBranch is required." }
    requireNotNull(reviewBaseline) {
      "reviewBaseline is required for goal continuation before implementation can begin."
    }
    parentWorkflowId?.let { require(it.isNotBlank()) { "parentWorkflowId must be non-blank when provided." } }
    lastResumableStep?.let { require(it.isNotBlank()) { "lastResumableStep must be non-blank when provided." } }
    subtaskName?.let { require(it.isNotBlank()) { "subtaskName must be non-blank when provided." } }
  }
}

public sealed interface FeatureTaskRuntimeRunReport {
  public val issueKey: String
  public val workflowId: String
  public val featureSize: String

  /** The non-default feature branch the run was pinned to, or null when not yet resolved. */
  public val resolvedBranch: String?

  public data class Completed(
    public override val issueKey: String,
    public override val workflowId: String,
    public override val featureSize: String,
    public val completedPhaseIds: List<String>,
    public override val resolvedBranch: String?,
    public val subtaskOutcome: FeatureTaskRuntimeSubtaskOutcome? = null,
  ) : FeatureTaskRuntimeRunReport

  public data class Blocked(
    public override val issueKey: String,
    public override val workflowId: String,
    public override val featureSize: String,
    public val lastIncompletePhase: String,
    public val blockedReason: String,
    public val completedPhaseIds: List<String>,
    public override val resolvedBranch: String?,
    public val subtaskOutcome: FeatureTaskRuntimeSubtaskOutcome? = null,
  ) : FeatureTaskRuntimeRunReport {
    init {
      require(lastIncompletePhase.isNotBlank()) {
        "FeatureTaskRuntimeRunReport.Blocked.lastIncompletePhase must be non-blank."
      }
      require(blockedReason.isNotBlank()) {
        "FeatureTaskRuntimeRunReport.Blocked.blockedReason must be non-blank."
      }
    }
  }

  /**
   * Non-terminal and resumable, distinct from [Blocked]: the subtask is waiting on the bounded
   * operator decision over `retry_fix`, `accept_and_advance`, and `abandon_subtask`. The persisted
   * review state, `review_base_sha`, baseline untracked inventory, and consumed pass count survive
   * intact, so resume never re-reserves a consumed pass.
   */
  public data class Paused(
    public override val issueKey: String,
    public override val workflowId: String,
    public override val featureSize: String,
    public val pausedPhase: String,
    public val pauseReason: String,
    public val resumableStep: String,
    public val completedPhaseIds: List<String>,
    public override val resolvedBranch: String?,
    public val subtaskOutcome: FeatureTaskRuntimeSubtaskOutcome? = null,
  ) : FeatureTaskRuntimeRunReport {
    init {
      require(pausedPhase.isNotBlank()) { "FeatureTaskRuntimeRunReport.Paused.pausedPhase must be non-blank." }
      require(pauseReason.isNotBlank()) { "FeatureTaskRuntimeRunReport.Paused.pauseReason must be non-blank." }
      require(resumableStep.isNotBlank()) { "FeatureTaskRuntimeRunReport.Paused.resumableStep must be non-blank." }
    }
  }

  public data class Decomposed(
    public override val issueKey: String,
    public override val workflowId: String,
    public override val featureSize: String,
    public val reason: String,
    public val completedPhaseIds: List<String>,
    public val parentSpecPath: String,
    public val decompositionManifestPath: String,
    public val subtaskSpecPaths: List<String>,
    public override val resolvedBranch: String?,
  ) : FeatureTaskRuntimeRunReport {
    init {
      require(reason.isNotBlank()) { "FeatureTaskRuntimeRunReport.Decomposed.reason must be non-blank." }
      require(parentSpecPath.isNotBlank()) {
        "FeatureTaskRuntimeRunReport.Decomposed.parentSpecPath must be non-blank."
      }
      require(decompositionManifestPath.isNotBlank()) {
        "FeatureTaskRuntimeRunReport.Decomposed.decompositionManifestPath must be non-blank."
      }
      require(subtaskSpecPaths.isNotEmpty()) {
        "FeatureTaskRuntimeRunReport.Decomposed.subtaskSpecPaths must not be empty."
      }
    }
  }
}

public data class FeatureTaskRuntimeSubtaskOutcome(
  public val issueKey: String,
  public val subtaskId: Int,
  public val status: GoalRunnerTerminalStatus,
  public val commitSha: String?,
  public val workflowId: String,
  public val blockedReason: String?,
  public val lastResumableStep: String,
  public val finalizingAgentId: String? = null,
  public val participatingAgentIds: List<String> = emptyList(),
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(subtaskId > 0) { "subtaskId must be positive." }
    require(status.wireValue.isNotBlank()) { "status is required." }
    require(workflowId.isNotBlank()) { "workflowId is required." }
    require(lastResumableStep.isNotBlank()) { "lastResumableStep is required." }
  }
}

internal sealed interface FeatureTaskRuntimePlanningStopDecision {
  data object Proceed : FeatureTaskRuntimePlanningStopDecision

  data class Decomposed(
    val report: FeatureTaskRuntimeRunReport.Decomposed,
  ) : FeatureTaskRuntimePlanningStopDecision

  data class Blocked(val reason: String) : FeatureTaskRuntimePlanningStopDecision
}

public sealed interface FeatureTaskRuntimeRunEvent {
  public val workflowId: String
  public val phaseId: String

  public data class RunStarted(
    public override val workflowId: String,
    public val featureSize: String,
  ) : FeatureTaskRuntimeRunEvent {
    override val phaseId: String = "run"
  }

  /**
   * Emitted once when the runtime establishes the run's feature branch before the first
   * file-mutating phase. [created] is true when the runtime created and switched to the branch,
   * false when it reused an already-checked-out non-default branch (including a resume re-attach).
   */
  public data class BranchResolved(
    public override val workflowId: String,
    public override val phaseId: String,
    public val branch: String,
    public val created: Boolean,
    public val reused: Boolean,
  ) : FeatureTaskRuntimeRunEvent

  /**
   * Emitted once when the runtime fails to establish the run's feature branch (missing/dirty git,
   * denied/non-landing checkout, unreadable HEAD, a deleted or unverifiable persisted branch, a
   * protected/wrong landed branch, or a branch that could not be durably recorded). Symmetric with
   * [PhaseBlocked]: the same block is also persisted as a durable blocked record and a ledger entry
   * so the failure is visible to status queries and the audit trail, not only the event stream.
   */
  public data class BranchSetupBlocked(
    public override val workflowId: String,
    public override val phaseId: String,
    public val blockedReason: String,
  ) : FeatureTaskRuntimeRunEvent

  public data class PhaseStarted(
    public override val workflowId: String,
    public override val phaseId: String,
    public val resolvedAgentId: String,
    public val attemptCount: Int,
    public val resumed: Boolean,
    public val model: String? = null,
    public val effort: String? = null,
    public val continuationKind: String? = null,
  ) : FeatureTaskRuntimeRunEvent

  /**
   * A backward-edge re-entry: a verifier (audit or review) sending work back to an earlier phase.
   *
   * Emitted so the event stream carries the same five-way continuation distinction the status
   * projection reports. Previously the loop edge appended a ledger entry but emitted no event, so a
   * telemetry consumer saw the re-entered phase simply start again with no stated cause.
   */
  public data class PhaseLoopEdge(
    public override val workflowId: String,
    public override val phaseId: String,
    public val loopId: String,
    public val edgeIteration: Int,
    public val drivingVerdict: String,
    public val continuationKind: String,
  ) : FeatureTaskRuntimeRunEvent

  public data class PhaseFixLoopIteration(
    public override val workflowId: String,
    public override val phaseId: String,
    public val resolvedAgentId: String,
    public val attemptCount: Int,
    public val fixLoopIteration: Int,
    public val continuationKind: String? = null,
  ) : FeatureTaskRuntimeRunEvent

  /** Runtime-owned validation gate progress while validate is running. */
  public data class ValidationGateProgress(
    public override val workflowId: String,
    public override val phaseId: String,
    public val gateRunCount: Int,
  ) : FeatureTaskRuntimeRunEvent

  /**
   * Pull request checks are pending or have not yet been reported. Emitted so run and IDE progress stays fresh
   * while CI registration or checks wait, without changing the verdict.
   */
  public data class CiStillRunning(
    public override val workflowId: String,
    public override val phaseId: String,
    public val pullRequestUrl: String,
    public val pendingChecks: List<PullRequestCheck>,
  ) : FeatureTaskRuntimeRunEvent

  public data class PhaseCompleted(
    public override val workflowId: String,
    public override val phaseId: String,
    public val resolvedAgentId: String,
    public val attemptCount: Int,
  ) : FeatureTaskRuntimeRunEvent

  public data class PhaseBlocked(
    public override val workflowId: String,
    public override val phaseId: String,
    public val resolvedAgentId: String,
    public val attemptCount: Int,
    public val blockedReason: String,
  ) : FeatureTaskRuntimeRunEvent

  /**
   * The phase stopped for a condition that clears without operator repair — today, a provider usage
   * limit refusing the launch. Deliberately not a [PhaseBlocked] with a different string: a consumer
   * that counts blocks would otherwise read a self-healing wait as a terminal failure, and the run is
   * resumable from exactly this phase.
   */
  public data class PhasePaused(
    public override val workflowId: String,
    public override val phaseId: String,
    public val resolvedAgentId: String,
    public val attemptCount: Int,
    public val pauseReason: String,
  ) : FeatureTaskRuntimeRunEvent

  public data class DecomposedAtPlanning(
    public override val workflowId: String,
    public override val phaseId: String,
    public val reason: String,
    public val subtaskCount: Int,
    public val parentSpecPath: String,
    public val decompositionManifestPath: String,
  ) : FeatureTaskRuntimeRunEvent
}

public fun interface FeatureTaskRuntimeRunEventSink {
  public fun emit(event: FeatureTaskRuntimeRunEvent): Unit

  public companion object {
    public val NONE: FeatureTaskRuntimeRunEventSink = FeatureTaskRuntimeRunEventSink {}
  }
}

public data class FeatureTaskRuntimeImplementationContinuation(
  public val phaseId: String,
  public val segmentNumber: Int,
  public val priorValueSegments: List<String>,
  public val latestPrompt: String?,
  public val failureDisposition: String?,
)

public data class FeatureTaskRuntimeImplementationTurn(
  val role: FeatureTaskRuntimeImplementationTurnRole,
  val criterionId: String,
  val criterion: String,
  val worker: Int,
  val specReference: String,
  val latestGap: String?,
) {
  init {
    require(role.launch) { "An implementation turn is a worker or a check launch, was ${role.wireValue}." }
  }

  internal val check: Boolean get() = role == FeatureTaskRuntimeImplementationTurnRole.CHECK_LAUNCH
}
