package dev.skillbill.desktop.core.domain.model

import kotlin.time.Instant

/** The detail lists that page on their own, one to one with the runtime's lists. */
enum class DetailList { Subtasks, Phases, Validation, Executions, Dependencies, PlanOutput }

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
  val changeBaseline: ChangeBaseline? = null,
  val degradedGoalStatusSource: String? = null,
  val currentSubtaskId: Int? = null,
  val goalWorktree: GoalWorktree? = null,
  val launchWarning: String? = null,
  val currentActivity: String? = null,
  val validationBranchRuns: List<ValidationBranchRun> = emptyList(),
  val selectedValidationResult: String? = null,
  val selectedExecution: ExecutionInstance? = null,
  val planActivity: List<PlanAgentActivity> = emptyList(),
  val planOutputLines: List<PlanOutputLine> = emptyList(),
  val planOutputTail: List<String> = emptyList(),
  val planOutputUpdatedAt: Instant? = null,
  val planSubtaskLatestLines: Map<Int, String> = emptyMap(),
  val standalonePlan: StandalonePlanProgress? = null,
  val verifyRun: VerifyRunDetails? = null,
)

data class ValidationBranchRun(
  val invocationId: String,
  val runSequence: String,
  val lifecycle: Lifecycle,
  val startedAt: Instant,
  val finishedAt: Instant?,
  val activeDurationMs: Long?,
  val agent: String?,
  val currentActivity: String?,
  val result: String?,
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
  val degradedReadSource: String? = null,
  val runningReviewPass: RunningReviewPass? = null,
  val auditResult: SubtaskAuditResult? = null,
  val execution: SubtaskExecution? = null,
)

data class SubtaskExecution(
  val workflowId: String,
  val agentId: String?,
  val model: String?,
  val effort: String?,
  val startedAt: Instant,
  val updatedAt: Instant,
  val phaseAttemptCounts: Map<String, Int>,
)

data class SubtaskAuditResult(
  val outcome: AuditOutcome,
  val metCount: Int?,
  val totalCount: Int?,
  val criteria: List<AuditCriterion>,
  val attempts: List<AuditAttempt>,
  val attemptTotal: Int?,
)

enum class AuditOutcome { Passed, Failed, Running, Unknown }

data class AuditCriterion(val ref: String, val text: String, val met: Boolean, val fixedInAttempt: Int?)

data class AuditAttempt(val number: Int, val passed: Boolean, val uncoveredCount: Int, val nextPhaseId: String?)

data class RunningReviewPass(val passNumber: Int, val lanes: List<RunningReviewLane>)

data class RunningReviewLane(val name: String, val finished: Boolean)

/** One goal subtask review pass, its findings as the review phase shows them; [lanes] is empty when the pass's review run is not stored. */
data class ReviewPass(
  val passNumber: Int,
  val verdict: String,
  val unresolvedFindingCount: Int,
  val findings: List<ReviewReportFinding>,
  val lanes: List<String> = emptyList(),
)

data class PhaseConclusion(val verdict: String?, val summary: String?, val detail: String?, val attemptCount: Int)

data class GoalQaResult(
  val outcome: String,
  val reason: String?,
  val fixAttempts: Int,
  val recordedAt: Instant,
  val findings: List<GoalQaFinding>,
  val reportText: String?,
  val failureKind: String?,
  val details: QaRunDetails?,
)

/** One finding goal QA reported; [fixed] is true when a later QA run no longer saw it. */
data class GoalQaFinding(
  val id: String,
  val title: String,
  val fixed: Boolean,
  val evidencePaths: List<String> = emptyList(),
  val reportPath: String? = null,
  val observed: String? = null,
  val expected: String? = null,
  val steps: String? = null,
)

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
  val qaChecklist: QaChecklist? = null,
  val qaFindings: List<QaChecklistFinding> = emptyList(),
  val qaDetails: QaRunDetails? = null,
  val liveChecks: Boolean = false,
  val validationOutcome: ValidationOutcome? = null,
  val validationChecks: List<ValidationCheck> = emptyList(),
)

enum class ValidationOutcome { Passed, Failed, Blocked }

enum class ValidationCheckResult { Passed, Failed, Blocked, Skipped }

data class ValidationCheck(
  val checkId: String,
  val name: String,
  val command: String,
  val workingDir: String,
  val attempts: List<ValidationCheckAttempt>,
)

data class ValidationCheckAttempt(
  val number: Int,
  val startedAt: Instant?,
  val finishedAt: Instant?,
  val result: ValidationCheckResult?,
  val exitCode: Int?,
  val note: String?,
)

/**
 * A standalone review's report as the runtime read it: the canonical verdict token, the prose around the findings, the findings,
 * and the review lanes (rubric skill names) it applied, in the order it named them.
 */
data class ReviewReport(val verdict: String?, val summary: String?, val findings: List<ReviewReportFinding>, val lanes: List<String> = emptyList(), val findingActivity: List<ActivityFact> = emptyList())

/** One reported finding; [specialist] is the review lane that raised it, [location] a `path:line` when the report gave one. */
data class ReviewReportFinding(
  val ref: String,
  val severity: String,
  val confidence: String?,
  val specialist: String?,
  val location: String?,
  val description: String,
  val status: ReviewFindingStatus = ReviewFindingStatus.Open,
)

/** Where a review finding stands: the operator's feedback on it, or else what the goal loop did with it. */
enum class ReviewFindingStatus { Open, Fixed, Accepted, Declined, FalsePositive, Rejected }

/** The feature a plan run settled and the subtasks it split the feature into; also a goal's spec bundle. */
data class PlanRunReport(
  val featureName: String,
  val parentSpecPath: String,
  val subtasks: List<PlannedSubtask>,
  val bundleFiles: List<String>,
)

data class PlannedSubtask(val id: Int, val name: String, val specPath: String, val dependsOn: List<Int>)

data class StandalonePlanProgress(
  val report: PlanRunReport?,
  val stages: List<StandalonePlanStage>,
  val plannedSubtaskCount: Int,
  val totalSubtaskCount: Int,
  val currentPlanningSubtaskId: Int?,
  val planningWaveSubtaskIds: List<Int>,
  val planningWaveCapacity: Int?,
  val blockedSubtaskId: Int?,
  val blockedReason: String?,
  val units: List<StandalonePlanUnit>,
  val stalledSince: Instant?,
  val planningWaveNumber: Int? = null,
  val planningWaveHistoryComplete: Boolean = false,
  val blockedSubtaskIds: List<Int> = emptyList(),
  val waves: List<PlanWave> = emptyList(),
)

data class StandalonePlanStage(
  val phaseId: String,
  val firstStartedAt: Instant?,
  val finishedAt: Instant?,
  val briefingText: String?,
  val status: String? = null,
)

data class StandalonePlanUnit(
  val subtaskId: Int,
  val name: String,
  val specPath: String,
  val dependsOn: List<Int>,
  val state: String,
  val startedAt: Instant?,
  val finishedAt: Instant?,
  val blockedReason: String?,
  val latestOutput: String?,
  val latestOutputAt: Instant? = null,
)

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

data class PlanOutputLine(val id: String, val text: String, val at: Instant, val subtaskId: Int?, val sequence: Long, val isRaw: Boolean = false)
data class PlanWave(val number: Int?, val subtaskIds: List<Int>, val startedAt: Instant?)

data class PlanAgentActivity(val id: String, val phaseId: String, val kind: String, val text: String,
  val at: Instant, val updatedAt: Instant, val agentId: String, val path: String?, val state: String,
  val durationMs: Long?, val exitCode: Int?, val subtaskId: Int?)
