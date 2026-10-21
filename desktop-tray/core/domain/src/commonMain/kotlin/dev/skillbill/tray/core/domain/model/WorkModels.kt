package dev.skillbill.tray.core.domain.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

enum class Lifecycle { ACTIVE, PAUSED, BLOCKED, FAILED, TERMINAL, CANCELED, IDLE }
enum class Freshness { FRESH, STALE, UNKNOWN }
enum class WorkFamily { FEATURE_GOAL, FEATURE_TASK_RUNTIME, FEATURE_VERIFY }
enum class ExecutionScope { WORKFLOW, STANDALONE_PHASE }
enum class Retention(val cliValue: String, val duration: Duration) {
  SIX_HOURS("6h", 6.hours), DAY("24h", 24.hours), WEEK("7d", 7.days)
}

data class WorkIdentity(val id: String, val repositoryIdentity: String)

data class DatabaseFileIdentity(val path: String, val fileKey: String)

data class WorkItem(
  val identity: WorkIdentity,
  val workflowId: String?,
  val invocationId: String?,
  val repositoryRoot: String,
  val branch: String?,
  val issueKey: String?,
  val family: WorkFamily?,
  val scope: ExecutionScope?,
  val phaseId: String?,
  val activity: String?,
  val lifecycle: Lifecycle,
  val freshness: Freshness,
  val summary: String,
  val currentStepId: String,
  val currentStepLabel: String,
  val progressCompleted: Int?,
  val progressTotal: Int?,
  val currentSubtaskId: String? = null,
  val currentSubtaskStartedAt: Instant? = null,
  val currentPhaseId: String? = null,
  val phaseExecution: PhaseExecution? = null,
  val currentModel: String? = null,
  val currentEffort: String? = null,
  val startedAt: Instant? = null,
  val lastAgentActivityLabel: String? = null,
  val lastAgentActivityAt: Instant? = null,
  /** When the runtime last saw durable progress or agent output on a run whose process is still alive; set only once that is past the runtime's stall threshold. */
  val stalledSince: Instant? = null,
  val activeDurationMs: Long?,
  val activeDurationAsOf: Instant?,
  val subtaskActiveDurationMs: Long?,
  val subtaskActiveDurationAsOf: Instant?,
  val pauseRequested: Boolean,
  val pauseReasonCode: String?,
  val pauseReason: String?,
  val planningState: String?,
  val plannedSubtasks: Int?,
  val totalSubtasks: Int?,
  val planningWaveSubtaskIds: List<String>,
  val executionCount: Int?,
  val executionTotal: Int?,
  val updatedAt: Instant,
  val finishedAt: Instant?,
  val finishedAtSource: String?,
  val executionId: String? = null,
  val statusStoreId: String? = null,
  val runSequence: String? = null,
  val statusRevision: String? = null,
  val databaseGeneration: Long = 0,
  val controlLeaseOwner: String? = null,
  val controlLeaseGeneration: Long? = null,
  val databaseStoreId: String? = null,
  val databaseRevision: Long? = null,
  val liveSection: WorkSection? = null,
  val controlEligible: Boolean = false,
  /** The runtime's verdict on a pause: [controlEligible] and no pause already pending. */
  val pauseEligible: Boolean = false,
  val requiresAttention: Boolean = false,
  val displayGroup: WorkSection? = null,
  val displayPriority: Int = 5,
  val exactPauseReason: String? = null,
  /** Runtime verdicts about the observation this item was read at; the app renders them and never recomputes them. */
  val running: Boolean = false,
  val recentlyFinishedPhase: Boolean = false,
  val doneWithinDay: Boolean = false,
  /** When [recentlyFinishedPhase] or [doneWithinDay] next changes, so the board is re-read then. */
  val membershipDeadline: Instant? = null,
  val databaseFile: DatabaseFileIdentity? = null,
  val repositoryName: String? = null,
  /** A goal planning before its goal exists: recorded as a standalone phase, shown as the goal. */
  val goalLaunched: Boolean = false,
  /** The verdict a standalone QA phase recorded; absent for every other kind of work. */
  val qaVerdict: QaVerdict? = null,
)

data class PhaseExecution(val phaseId: String, val kind: PhaseExecutionKind, val count: Int, val total: Int?)
enum class PhaseExecutionKind { PASS, SEMANTIC_LOOP, GATE_RUN, BOUNDED_EDGE, ATTEMPT }

enum class DetailList { SUBTASKS, PHASES, VALIDATION, EXECUTIONS, DEPENDENCIES }

data class Detail(
  val item: WorkItem,
  val stepPipeline: List<String>,
  val goalFinishPipeline: List<String>,
  val featureTitle: String?,
  val currentPhase: String?,
  val completeCount: Int,
  val pendingCount: Int,
  val blockedCount: Int,
  val activeAgent: String?,
  val executionLiveness: String?,
  val extras: Map<String, String>,
  val subtasks: List<WorkSubtask> = emptyList(),
  val phases: List<WorkPhase> = emptyList(),
  val attempts: DetailAttempts = DetailAttempts(),
  val validation: List<SubtaskValidation> = emptyList(),
  val phaseResults: List<SubtaskPhaseResults> = emptyList(),
  val goalQa: GoalQaResult? = null,
  val latestActivity: LatestActivity = LatestActivity(),
  val executionHistory: List<ExecutionInstance> = emptyList(),
  val observedAt: Instant? = null,
  val nextCursor: String? = null,
  val pageOffset: Int = 0,
  val cursors: Map<DetailList, String> = emptyMap(),
  /** Per list, the opaque identity this page started after; absent for a head page. Pass it back to refresh the page. */
  val anchors: Map<DetailList, String> = emptyMap(),
  val dependencies: List<SubtaskDependency> = emptyList(),
  val freshnessDeadline: Instant? = null,
  val runOptions: RunOptions? = null,
)

data class DetailAttempts(
  val phaseAttempts: Map<String, Int> = emptyMap(),
  val fixIterations: Map<String, Int> = emptyMap(),
  val reAttemptCauses: Map<String, Int> = emptyMap(),
  val findingsInScope: Int? = null,
  val supervisorKills: Int = 0,
)

data class SubtaskValidation(
  val subtaskId: Int,
  val passed: Boolean?,
  val integrityProblem: String?,
  val evidence: List<ValidationCommand>,
  val result: ValidationResult?,
)
/** Retained review and audit results of the current subtask or a completed subtask. */
data class SubtaskPhaseResults(
  val subtaskId: Int,
  val reviewPasses: List<ReviewPass>,
  val audit: PhaseConclusion?,
  val review: PhaseConclusion? = null,
)
data class ReviewPass(val passNumber: Int, val verdict: String, val unresolvedFindingCount: Int, val findings: List<ReviewFinding>)
data class ReviewFinding(val severity: String, val label: String, val text: String)
data class PhaseConclusion(val verdict: String?, val summary: String?, val detail: String?, val attemptCount: Int)
data class GoalQaResult(
  val outcome: String,
  val reason: String?,
  val fixAttempts: Int,
  val recordedAt: Instant,
  val findings: List<GoalQaFinding> = emptyList(),
  val criteria: List<QaCriterionResult> = emptyList(),
)
/** One finding goal QA reported; [fixed] is true when a later QA run no longer saw it. */
data class GoalQaFinding(val id: String, val title: String, val evidencePath: String?, val fixed: Boolean)
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
data class LatestActivity(
  val event: ActivityEvent? = null,
  val edit: WorktreeEdit? = null,
)
data class ActivityEvent(val workflowPhase: String?, val workerRole: String?, val summary: String?, val at: Instant?)
data class WorktreeEdit(val recordedAt: Instant?, val paths: List<String>, val insertions: Int?, val deletions: Int?, val phaseId: String?)

data class WorkSubtask(
  val id: Int,
  val name: String,
  val status: String,
  val commitSha: String?,
  val blockedReason: String?,
  val dependencies: List<WorkDependency>,
)

data class WorkDependency(val subtaskId: Int, val optional: Boolean, val skipped: Boolean)

data class WorkPhase(val id: String, val status: String, val attemptCount: Int, val finished: Boolean)

data class DetailLookup(val item: Detail?, val problem: Problem?)

data class Board(
  val observedAt: Instant,
  val finishedWithin: Retention,
  val items: List<WorkItem>,
  val problem: Problem? = null,
  val diagnostics: List<String> = emptyList(),
  val nextCursor: String? = null,
  val generation: Long = 0,
  val observationSession: Long = 0,
  val databasePath: String? = null,
  val storeId: String? = null,
  val revision: Long? = null,
  val pageKey: String = "root",
  val queryEpoch: Long = 0,
  val filter: String = "",
  val historyFilter: HistoryFilter = HistoryFilter.ALL,
)

/** Aggregate status of a board, decided by the runtime; the app only renders it. */
enum class TrayStatus { FAILED, NEEDS_YOU, RUNNING, IDLE }

/** The runtime's grouping, ordering and counts for a board; the lists are already in display order. */
data class BoardArrangement(
  val trayStatus: TrayStatus,
  val running: Int,
  val needsYou: Int,
  val doneWithinDay: Int,
  val goals: List<WorkIdentity>,
  val livePhases: List<WorkIdentity>,
  val finishedPhases: List<WorkIdentity>,
  val liveOperations: List<WorkIdentity> = emptyList(),
  val finishedOperations: List<WorkIdentity> = emptyList(),
)

data class BoardRetention(
  val section: WorkSection,
  val retention: Retention,
  val filter: String,
  val historyFilter: HistoryFilter,
  val queryEpoch: Long,
  val generation: Long,
  val observationSession: Long,
  val storeId: String?,
  val revision: Long?,
  val pageKeys: List<String>,
)

enum class StorageFailure(val code: String) {
  MISSING("missing_database"), ACCESS("database_access"), BUSY("busy"), CORRUPT("corrupt_database"),
  INCOMPATIBLE_SCHEMA("incompatible_database"), INCOMPATIBLE_RECORD("incompatible_record"),
  OBSERVATION_UNAVAILABLE("observation_unavailable"), STALE_QUERY("stale_target"),
}

enum class ProblemCode(val wireValue: String) {
  SETTINGS_UNAVAILABLE("settings_unavailable"),
  SETTINGS_CORRUPT("settings_corrupt"),
  SETTINGS_UPDATE_FAILED("settings_update_failed"),
  LOGIN_SETTING_FAILED("login_setting_failed"),
  STALE_TARGET("stale_target"),
  DATABASE_ACCESS("database_access"),
  MISSING_DATABASE("missing_database"),
  BUSY("busy"),
  CORRUPT_DATABASE("corrupt_database"),
  INCOMPATIBLE_DATABASE("incompatible_database"),
  INCOMPATIBLE_RECORD("incompatible_record"),
  OBSERVATION_UNAVAILABLE("observation_unavailable"),
  TRAY_UNAVAILABLE("tray_unavailable"),
  UNKNOWN_WORK_ITEM("unknown_work_item"),
  RUNTIME_BUILD_MISMATCH("runtime_build_mismatch"),
  UNKNOWN("unknown"),
  ;

  companion object {
    fun fromWire(value: String): ProblemCode = entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
  }
}

data class Problem(
  val code: ProblemCode,
  val summary: String,
  val storageFailure: StorageFailure? = null,
  val runtimeBuild: RuntimeBuildProblem? = null,
)

/** Why the installed runtime was refused: no runtime, no record, a record that cannot be read, or another build's record. */
enum class RuntimeBuildReason { MISMATCH, MISSING, UNREADABLE, NOT_INSTALLED }

/** The desktop refused the installed runtime. Both builds are display text; [installedIdentity] is null when there is none. */
data class RuntimeBuildProblem(
  val reason: RuntimeBuildReason,
  val installedIdentity: String?,
  val desktopIdentity: String,
  val reinstallCommand: String,
)

/** Selecting a database was refused before anything opened it, because the installed runtime is not this desktop's build. */
class RuntimeBuildRefusedException(val problem: RuntimeBuildProblem) :
  Exception("The installed runtime build is refused (${problem.reason}): installed ${problem.installedIdentity}, desktop ${problem.desktopIdentity}.")

/** What a goal control request achieved. Feedback names exactly this and never implies a stronger lifecycle change. */
enum class ControlConfirmation {
  /** The control is durably recorded; the runner has not acted on it yet. */
  INTENT_RECORDED,

  /** The request was admitted with no process to wait for. */
  REQUEST_ACCEPTED,

  /** The runner's process was confirmed to have exited. */
  EXIT_CONFIRMED,

  /** A stop signal was sent but the runner's exit could not be confirmed. */
  TERMINATION_ATTEMPTED,
}

enum class HistoryFilter { ALL, GOALS, PHASES, OPERATIONS }

data class AppSettings(
  val databasePath: String? = null,
  val retention: Retention = Retention.DAY,
  val historyFilter: HistoryFilter = HistoryFilter.ALL,
  val notifications: Map<String, Boolean> = defaultNotifications,
  val startAtLogin: Boolean = true,
  val lastPopoverTab: String = "Goals",
) {
  init {
    require(lastPopoverTab in setOf("Goals", "Phases"))
  }
}

/** Readiness of persisted preferences; [Ready] is only published after the first persisted read completes. */
sealed interface SettingsState {
  data object Loading : SettingsState
  data class Ready(val settings: AppSettings) : SettingsState
  data class Failed(val problem: Problem) : SettingsState
}

enum class WorkSection { NEEDS_YOU, GOALS, PHASES, OPERATIONS, HISTORY, RECENT }

val allowedTransitions = listOf("blocked", "failed", "goal_completed", "pause_took_effect", "standalone_phase_finished")
val defaultNotifications = mapOf("blocked" to true, "failed" to true, "goal_completed" to true, "pause_took_effect" to true, "standalone_phase_finished" to false)

fun WorkItem.needsAttention(): Boolean =
  requiresAttention

/** The runtime judged this active run stalled: its process is alive but it has made no progress past the threshold. */
fun WorkItem.stalled(): Boolean = lifecycle == Lifecycle.ACTIVE && stalledSince != null

fun WorkItem.successfulGoalCompletion(): Boolean =
  family == WorkFamily.FEATURE_GOAL &&
    lifecycle == Lifecycle.TERMINAL &&
    finishedAtSource == "goal_completion"

fun WorkItem.isPhase(): Boolean = displayGroup == WorkSection.PHASES

/** Whether the item reads as a standalone phase; a goal's plan that runs before its goal exists reads as that goal. */
fun WorkItem.showsAsStandalonePhase(): Boolean = scope == ExecutionScope.STANDALONE_PHASE && !goalLaunched

/** Whether a relaunched attempt has begun running or finished; used only to recognise a launch's successor, never to group work. */
fun WorkItem.hasStartedOrFinished(): Boolean = lifecycle == Lifecycle.ACTIVE || lifecycle == Lifecycle.TERMINAL

/** The runtime's freshness verdict read as a typed fact; transition detection only trusts fresh observations. */
fun WorkItem.isFresh(): Boolean = freshness == Freshness.FRESH

fun WorkItem.eligibleForControl(): Boolean =
  controlEligible

/** How an execution's durable reason was recovered; only [EXACT] and [RESTORED] carry the full original text. */
enum class ReasonRestoration { EXACT, RESTORED, MISSING, AMBIGUOUS, BUDGET_EXCEEDED, MISSING_CONTENT }

data class ExecutionInstance(
  val invocationId: String,
  val executionId: String,
  val lifecycleState: String,
  val startedAt: Instant,
  val updatedAt: Instant,
  val finishedAt: Instant?,
  val result: String?,
  val restoration: ReasonRestoration? = null,
  val qaVerdict: QaVerdict? = null,
  val qaReport: QaRunReport? = null,
  val planReport: PlanRunReport? = null,
)

/** The feature a standalone plan run settled and the subtasks it split the feature into. */
data class PlanRunReport(val featureName: String, val parentSpecPath: String, val subtasks: List<PlannedSubtask>)
data class PlannedSubtask(val id: Int, val name: String, val specPath: String, val dependsOn: List<Int>)

data class SubtaskDependency(val subtaskId: Int, val dependency: WorkDependency)

data class RunOptions(
  val canCancel: Boolean,
  val canLaunch: Boolean,
  val launchLabel: String,
  val agentId: String?,
  val agentChoices: List<String>,
  val unavailableReason: String?,
)
