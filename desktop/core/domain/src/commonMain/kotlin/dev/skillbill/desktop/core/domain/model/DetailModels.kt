package dev.skillbill.desktop.core.domain.model

import kotlin.time.Instant

/** The detail lists that page on their own, one to one with the runtime's lists. */
enum class DetailList { Subtasks, Phases, Validation, Executions, Dependencies }

/** What the runtime allows for running one item again; the app renders it and decides nothing. */
data class RunOptions(
  val canCancel: Boolean,
  val canLaunch: Boolean,
  val launchLabel: String,
  val agentId: String?,
  val agentChoices: List<String>,
  val unavailableReason: String?,
)

/** An open detail: [header] paints at once from the row, [sections] arrive from the first read. */
data class DetailState(val header: WorkItem, val sections: DetailSections)

sealed interface DetailSections {
  data object Pending : DetailSections

  /**
   * [stale] means the last refresh failed and these are the previous results. [listCursors] holds, per list in [moreLists],
   * the opaque token `loadList` takes to read that list's next page.
   */
  data class Loaded(
    val detail: Detail,
    val runOptions: RunOptions?,
    val listCursors: Map<DetailList, String>,
    val moreLists: Set<DetailList>,
    val stale: Boolean,
  ) : DetailSections

  /** The runtime no longer knows the item. */
  data object Gone : DetailSections

  data class Failed(val problem: ReadProblem) : DetailSections
}

sealed interface DetailListOutcome {
  data object Appended : DetailListOutcome

  data object End : DetailListOutcome

  data class Failed(val problem: ReadProblem) : DetailListOutcome
}

/** The standalone QA verdict a phase recorded. */
enum class QaVerdict { Attempted, Passed, Findings, Skipped, Incomplete }

/** A workflow's recorded QA outcome, by wire value. */
data class WorkQaOutcome(val outcome: QaVerdict, val reason: String?, val failureKind: String?, val recordedAt: Instant) {
  val couldNotComplete: Boolean get() = outcome == QaVerdict.Incomplete && failureKind != null
}

/**
 * Every detail section below the header, as the runtime read it. The execution facts (workflow, invocation, agent, model,
 * liveness, started, last update) and runs and fixes ([attempts]) back the details pane.
 */
data class Detail(
  val featureTitle: String?,
  val workflowId: String?,
  val invocationId: String?,
  val activeAgent: String?,
  val executionLiveness: String?,
  val updatedAt: Instant,
  val lastAgentActivityLabel: String?,
  val lastAgentActivityAt: Instant?,
  val currentSubtaskStartedAt: Instant?,
  val subtaskActiveDurationMs: Long?,
  val subtaskActiveDurationAsOf: Instant?,
  val qaVerdict: QaVerdict?,
  val qaOutcome: WorkQaOutcome?,
  val stepPipeline: List<String>,
  val goalFinishPipeline: List<String>,
  val currentPhase: String?,
  val completeCount: Int,
  val pendingCount: Int,
  val blockedCount: Int,
  val subtasks: List<WorkSubtask>,
  val phases: List<WorkPhase>,
  val dependencies: List<SubtaskDependency>,
  val attempts: DetailAttempts,
  val validation: List<SubtaskValidation>,
  val phaseResults: List<SubtaskPhaseResults>,
  val goalQa: GoalQaResult?,
  val goalAssessment: GoalAssessmentResult?,
  val latestActivity: LatestActivity,
  val executionHistory: List<ExecutionInstance>,
  val incident: Incident?,
  val incidentHistory: List<Incident>,
  val goalSpecBundle: PlanRunReport?,
  val observedAt: Instant,
)

data class WorkSubtask(
  val id: Int,
  val name: String,
  val status: String,
  val commitSha: String?,
  val blockedReason: String?,
  val dependencies: List<WorkDependency>,
)

data class WorkDependency(val subtaskId: Int, val optional: Boolean, val skipped: Boolean)

data class SubtaskDependency(val subtaskId: Int, val dependency: WorkDependency)

/** One phase of the run; [briefingText] is the launch briefing the runtime saved for it, when it has one. */
data class WorkPhase(val id: String, val status: String, val attemptCount: Int, val finished: Boolean, val briefingText: String? = null)

/** Runs and fixes: per-phase runs, fix iterations, relaunch causes, findings in scope and supervisor kills. */
data class DetailAttempts(
  val phaseAttempts: Map<String, Int>,
  val fixIterations: Map<String, Int>,
  val reAttemptCauses: Map<String, Int>,
  val findingsInScope: Int?,
  val supervisorKills: Int,
)

data class SubtaskValidation(
  val subtaskId: Int,
  val passed: Boolean?,
  val integrityProblem: String?,
  val evidence: List<ValidationCommand>,
  val result: ValidationResult?,
)

data class ValidationCommand(val command: String, val exitCode: Int)

data class ValidationResult(val status: String, val checks: List<String>, val gateRunCount: Int?, val gates: List<ValidationGateRun>)

data class ValidationGateRun(
  val durationMs: Long,
  val outcome: String,
  val cacheMode: String,
  val executedWorkUnits: Int,
  val executedChecks: List<String>,
  val command: String?,
  val exitCode: Int?,
)

/** Retained review and audit results of the current or a completed subtask. */
data class SubtaskPhaseResults(
  val subtaskId: Int,
  val reviewPasses: List<ReviewPass>,
  val audit: PhaseConclusion?,
  val review: PhaseConclusion?,
)

data class ReviewPass(val passNumber: Int, val verdict: String, val unresolvedFindingCount: Int, val findings: List<ReviewFinding>)

data class ReviewFinding(val severity: String, val label: String, val text: String)

data class PhaseConclusion(val verdict: String?, val summary: String?, val detail: String?, val attemptCount: Int)

data class GoalQaResult(
  val outcome: String,
  val reason: String?,
  val fixAttempts: Int,
  val recordedAt: Instant,
  val findings: List<GoalQaFinding>,
  val reportText: String?,
  val failureKind: String?,
)

/** One finding goal QA reported; [fixed] is true when a later QA run no longer saw it. */
data class GoalQaFinding(val id: String, val title: String, val evidencePath: String?, val fixed: Boolean)

enum class GoalAssessmentState { Pending, Running, Blocked, Completed, Skipped }

enum class GoalAssessmentVerification { Confirmed, Refuted, Unverified, NotRequired }

enum class GoalAssessmentFindingState { Open, Fixed, Refuted }

/** The goal assessment stage; [exitReason] is null until the stage settles. */
data class GoalAssessmentResult(
  val state: GoalAssessmentState,
  val round: Int,
  val exitReason: String?,
  val fixRan: Boolean,
  val findings: List<GoalAssessmentFinding>,
)

data class GoalAssessmentFinding(
  val ref: String,
  val severity: String,
  val path: String,
  val line: Int,
  val failureScenario: String,
  val verification: GoalAssessmentVerification,
  val state: GoalAssessmentFindingState,
  val evidence: String,
)

data class LatestActivity(val event: ActivityEvent?, val edit: WorktreeEdit?)

data class ActivityEvent(val workflowPhase: String?, val workerRole: String?, val summary: String?, val at: Instant?)

data class WorktreeEdit(val recordedAt: Instant?, val paths: List<String>, val insertions: Int?, val deletions: Int?, val phaseId: String?)

/** How an execution's durable reason was recovered; only [Exact] and [Restored] carry the full original text. */
enum class ReasonRestoration { Exact, Restored, Missing, Ambiguous, BudgetExceeded, MissingContent }

data class ExecutionInstance(
  val invocationId: String,
  val executionId: String,
  val lifecycleState: String,
  val startedAt: Instant,
  val updatedAt: Instant,
  val finishedAt: Instant?,
  val result: String?,
  val restoration: ReasonRestoration?,
  val qaVerdict: QaVerdict?,
  val qaReportText: String?,
  val planReport: PlanRunReport?,
  val reviewReport: ReviewReport? = null,
)

/** A standalone review's report as the runtime read it: the canonical verdict token, the prose around the findings, and the findings. */
data class ReviewReport(val verdict: String?, val summary: String?, val findings: List<ReviewReportFinding>)

/** One reported finding; [specialist] is the review lane that raised it, [location] a `path:line` when the report gave one. */
data class ReviewReportFinding(
  val ref: String,
  val severity: String,
  val confidence: String?,
  val specialist: String?,
  val location: String?,
  val description: String,
)

/** The feature a plan run settled and the subtasks it split the feature into; also a goal's spec bundle. */
data class PlanRunReport(
  val featureName: String,
  val parentSpecPath: String,
  val subtasks: List<PlannedSubtask>,
  val bundleFiles: List<String>,
)

data class PlannedSubtask(val id: Int, val name: String, val specPath: String, val dependsOn: List<Int>)

enum class IncidentProgress {
  InvestigationPending, Investigating, DiagnosisComplete, DiagnosisPartial, DiagnosisFailed,
  NeedsPrerequisites, RecoveryAvailable, Recovering, RecoveryFailed, Resumed,
}

enum class IncidentRecoveryUnavailableReason {
  NoSupportedAction, InvestigationNotSettled, NotBlocked, GenerationChanged, AttemptLimitReached, OperatorDecision, QualityGate,
}

data class IncidentPrerequisiteDetail(val kind: String, val instruction: String)

data class IncidentEvidence(val kind: String, val referenceId: String, val summary: String, val trusted: Boolean)

data class IncidentRecoveryPreview(
  val action: String,
  val label: String,
  val effects: String?,
  val targetWorkflowId: String?,
  val targetInvocationId: String?,
  val phaseId: String?,
  val incidentToken: String,
  val incidentRevision: Long,
)

sealed interface IncidentRecoveryEligibility {
  data class Available(val preview: IncidentRecoveryPreview) : IncidentRecoveryEligibility

  data class NeedsPrerequisites(val instructions: List<IncidentPrerequisiteDetail>) : IncidentRecoveryEligibility

  data class Unavailable(val reason: IncidentRecoveryUnavailableReason) : IncidentRecoveryEligibility
}

data class IncidentRecovery(
  val eligibility: IncidentRecoveryEligibility?,
  val previewLabel: String?,
  val effects: String?,
  val targetWorkflowId: String?,
  val targetInvocationId: String?,
  val latestAttemptId: String?,
  val latestAttemptStatus: String?,
  val failure: String?,
)

data class IncidentReport(val status: String, val failureReason: String?, val issueUrl: String?)

data class IncidentTelemetry(val availability: String, val searchIdentifiers: Map<String, String>)

data class Incident(
  val incidentToken: String,
  val revision: Long,
  val progress: IncidentProgress,
  val summary: String,
  val cause: String?,
  val certainty: String,
  val responsibleCategory: String,
  val nextAction: String,
  val noWorkaroundKnown: Boolean,
  val prerequisites: List<IncidentPrerequisiteDetail>,
  val evidence: List<IncidentEvidence>,
  val degradations: Set<String>,
  val recovery: IncidentRecovery,
  val report: IncidentReport,
  val telemetry: IncidentTelemetry,
  val observedAt: Instant,
  val settledAt: Instant?,
)
