package skillbill.engine.featuretask.model.core

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.decomposition.decompositionManifestPath
import skillbill.application.decomposition.parentSpecPath
import skillbill.config.model.CompactionSettings
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.goalreview.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import java.nio.file.Path
import kotlin.time.Duration

data class FeatureTaskRuntimeRunRequest(
  override val issueKey: String,
  override val workflowId: String,
  val sessionId: String,
  override val runInvariants: FeatureTaskRuntimeRunInvariants,
  override val invokedAgentId: String,
  override val agentAssignment: FeatureTaskRuntimeAgentAssignment = FeatureTaskRuntimeAgentAssignment(),
  override val modelAssignment: FeatureTaskRuntimeModelAssignment = FeatureTaskRuntimeModelAssignment(),
  override val compactionSettings: CompactionSettings = CompactionSettings.DEFAULT,
  override val environment: Map<String, String> = emptyMap(),
  override val repoRoot: Path,
  override val timeout: Duration? = null,
  override val requestedCodeReviewMode: CodeReviewExecutionMode? = null,
  override val goalContinuation: FeatureTaskRuntimeGoalContinuationContext? = null,
  val operatorDecision: GoalSubtaskOperatorDecision? = null,
  override val agentAddonSelection: HydratedAgentAddonSelection = HydratedAgentAddonSelection(),
  override val eventSink: FeatureTaskRuntimeRunEventSink = FeatureTaskRuntimeRunEventSink.NONE,
  override val transitionsOverride: FeatureTaskRuntimeTransitionDeclaration? = null,
  override val admittedExecution: AdmittedFeatureTaskRuntimeExecution? = null,
) : FeatureTaskRuntimeRunFacts {
  init {
    require(issueKey.isNotBlank()) { "FeatureTaskRuntimeRunRequest.issueKey is required." }
    require(workflowId.isNotBlank()) { "FeatureTaskRuntimeRunRequest.workflowId is required." }
    require(invokedAgentId.isNotBlank()) {
      "FeatureTaskRuntimeRunRequest.invokedAgentId is required; it is the documented default agent."
    }
  }
}

data class FeatureTaskRuntimeRunInput(
  val issueKey: String,
  val specPath: String,
  val repoRoot: Path,
  val explicitWorkflowId: String?,
  val invokedAgentId: String,
  val agentAssignment: FeatureTaskRuntimeAgentAssignment,
  val modelAssignment: FeatureTaskRuntimeModelAssignment,
  val compactionSettings: CompactionSettings,
  val environment: Map<String, String>,
  val timeout: Duration?,
  val requestedCodeReviewMode: CodeReviewExecutionMode?,
  val goalContinuation: FeatureTaskRuntimeGoalContinuationContext?,
  val operatorDecision: GoalSubtaskOperatorDecision?,
  val agentAddonSelection: HydratedAgentAddonSelection,
  val eventSink: FeatureTaskRuntimeRunEventSink,
)

data class FeatureTaskRuntimeGoalContinuationContext(
  val parentIssueKey: String,
  val subtaskId: Int,
  val goalBranch: String,
  val suppressPr: Boolean,
  val parentWorkflowId: String? = null,
  val lastResumableStep: String? = null,
  val codeReviewMode: CodeReviewExecutionMode? = null,
  val validationDepth: ValidationDepth = ValidationDepth.DEFAULT,
  val qualityGateSelection: FeatureTaskRuntimeQualityGateSelection = FeatureTaskRuntimeQualityGateSelection.VALIDATE,
  val subtaskName: String? = null,
  val reviewBaseline: GoalSubtaskReviewBaseline? = null,
  val agentAddonSelection: AgentAddonSelection =
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

sealed interface FeatureTaskRuntimeRunReport {
  val issueKey: String
  val workflowId: String
  val featureSize: String

  /** The non-default feature branch the run was pinned to, or null when not yet resolved. */
  val resolvedBranch: String?

  data class Completed(
    override val issueKey: String,
    override val workflowId: String,
    override val featureSize: String,
    val completedPhaseIds: List<String>,
    override val resolvedBranch: String?,
    val subtaskOutcome: FeatureTaskRuntimeSubtaskOutcome? = null,
  ) : FeatureTaskRuntimeRunReport

  data class Blocked(
    override val issueKey: String,
    override val workflowId: String,
    override val featureSize: String,
    val lastIncompletePhase: String,
    val blockedReason: String,
    val completedPhaseIds: List<String>,
    override val resolvedBranch: String?,
    val subtaskOutcome: FeatureTaskRuntimeSubtaskOutcome? = null,
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
  data class Paused(
    override val issueKey: String,
    override val workflowId: String,
    override val featureSize: String,
    val pausedPhase: String,
    val pauseReason: String,
    val resumableStep: String,
    val completedPhaseIds: List<String>,
    override val resolvedBranch: String?,
    val subtaskOutcome: FeatureTaskRuntimeSubtaskOutcome? = null,
  ) : FeatureTaskRuntimeRunReport {
    init {
      require(pausedPhase.isNotBlank()) { "FeatureTaskRuntimeRunReport.Paused.pausedPhase must be non-blank." }
      require(pauseReason.isNotBlank()) { "FeatureTaskRuntimeRunReport.Paused.pauseReason must be non-blank." }
      require(resumableStep.isNotBlank()) { "FeatureTaskRuntimeRunReport.Paused.resumableStep must be non-blank." }
    }
  }

  data class Decomposed(
    override val issueKey: String,
    override val workflowId: String,
    override val featureSize: String,
    val reason: String,
    val completedPhaseIds: List<String>,
    val parentSpecPath: String,
    val decompositionManifestPath: String,
    val subtaskSpecPaths: List<String>,
    override val resolvedBranch: String?,
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

data class FeatureTaskRuntimeSubtaskOutcome(
  val issueKey: String,
  val subtaskId: Int,
  val status: GoalRunnerTerminalStatus,
  val commitSha: String?,
  val workflowId: String,
  val blockedReason: String?,
  val lastResumableStep: String,
  val finalizingAgentId: String? = null,
  val participatingAgentIds: List<String> = emptyList(),
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(subtaskId > 0) { "subtaskId must be positive." }
    require(status.wireValue.isNotBlank()) { "status is required." }
    require(workflowId.isNotBlank()) { "workflowId is required." }
    require(lastResumableStep.isNotBlank()) { "lastResumableStep is required." }
  }
}

sealed interface FeatureTaskRuntimePlanningStopDecision {
  data object Proceed : FeatureTaskRuntimePlanningStopDecision

  data class Decomposed(
    val report: FeatureTaskRuntimeRunReport.Decomposed,
  ) : FeatureTaskRuntimePlanningStopDecision

  data class Blocked(val reason: String) : FeatureTaskRuntimePlanningStopDecision
}

sealed interface FeatureTaskRuntimeRunEvent {
  val workflowId: String
  val phaseId: String

  data class RunStarted(
    override val workflowId: String,
    val featureSize: String,
  ) : FeatureTaskRuntimeRunEvent {
    override val phaseId: String = "run"
  }

  /**
   * Emitted once when the runtime establishes the run's feature branch before the first
   * file-mutating phase. [created] is true when the runtime created and switched to the branch,
   * false when it reused an already-checked-out non-default branch (including a resume re-attach).
   */
  data class BranchResolved(
    override val workflowId: String,
    override val phaseId: String,
    val branch: String,
    val created: Boolean,
    val reused: Boolean,
  ) : FeatureTaskRuntimeRunEvent

  /**
   * Emitted once when the runtime fails to establish the run's feature branch (missing/dirty git,
   * denied/non-landing checkout, unreadable HEAD, a deleted or unverifiable persisted branch, a
   * protected/wrong landed branch, or a branch that could not be durably recorded). Symmetric with
   * [PhaseBlocked]: the same block is also persisted as a durable blocked record and a ledger entry
   * so the failure is visible to status queries and the audit trail, not only the event stream.
   */
  data class BranchSetupBlocked(
    override val workflowId: String,
    override val phaseId: String,
    val blockedReason: String,
  ) : FeatureTaskRuntimeRunEvent

  data class PhaseStarted(
    override val workflowId: String,
    override val phaseId: String,
    val resolvedAgentId: String,
    val attemptCount: Int,
    val resumed: Boolean,
    val model: String? = null,
    val effort: String? = null,
    val continuationKind: String? = null,
  ) : FeatureTaskRuntimeRunEvent

  /**
   * A backward-edge re-entry: a verifier (audit or review) sending work back to an earlier phase.
   *
   * Emitted so the event stream carries the same five-way continuation distinction the status
   * projection reports. Previously the loop edge appended a ledger entry but emitted no event, so a
   * telemetry consumer saw the re-entered phase simply start again with no stated cause.
   */
  data class PhaseLoopEdge(
    override val workflowId: String,
    override val phaseId: String,
    val loopId: String,
    val edgeIteration: Int,
    val drivingVerdict: String,
    val continuationKind: String,
  ) : FeatureTaskRuntimeRunEvent

  data class PhaseFixLoopIteration(
    override val workflowId: String,
    override val phaseId: String,
    val resolvedAgentId: String,
    val attemptCount: Int,
    val fixLoopIteration: Int,
    val continuationKind: String? = null,
  ) : FeatureTaskRuntimeRunEvent

  /** Runtime-owned validation gate progress while validate is running. */
  data class ValidationGateProgress(
    override val workflowId: String,
    override val phaseId: String,
    val gateRunCount: Int,
  ) : FeatureTaskRuntimeRunEvent

  data class PhaseCompleted(
    override val workflowId: String,
    override val phaseId: String,
    val resolvedAgentId: String,
    val attemptCount: Int,
  ) : FeatureTaskRuntimeRunEvent

  data class PhaseBlocked(
    override val workflowId: String,
    override val phaseId: String,
    val resolvedAgentId: String,
    val attemptCount: Int,
    val blockedReason: String,
  ) : FeatureTaskRuntimeRunEvent

  /**
   * The phase stopped for a condition that clears without operator repair — today, a provider usage
   * limit refusing the launch. Deliberately not a [PhaseBlocked] with a different string: a consumer
   * that counts blocks would otherwise read a self-healing wait as a terminal failure, and the run is
   * resumable from exactly this phase.
   */
  data class PhasePaused(
    override val workflowId: String,
    override val phaseId: String,
    val resolvedAgentId: String,
    val attemptCount: Int,
    val pauseReason: String,
  ) : FeatureTaskRuntimeRunEvent

  data class DecomposedAtPlanning(
    override val workflowId: String,
    override val phaseId: String,
    val reason: String,
    val subtaskCount: Int,
    val parentSpecPath: String,
    val decompositionManifestPath: String,
  ) : FeatureTaskRuntimeRunEvent
}

fun interface FeatureTaskRuntimeRunEventSink {
  fun emit(event: FeatureTaskRuntimeRunEvent)

  companion object {
    val NONE: FeatureTaskRuntimeRunEventSink = FeatureTaskRuntimeRunEventSink {}
  }
}

data class FeatureTaskRuntimeImplementationContinuation(
  val phaseId: String,
  val segmentNumber: Int,
  val priorValueSegments: List<String>,
  val latestPrompt: String?,
  val failureDisposition: String?,
)
