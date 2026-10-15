package dev.skillbill.tray.domain

import java.time.Instant
import skillbill.ports.filesystem.model.DatabaseFileObservation

enum class Lifecycle { ACTIVE, PAUSED, BLOCKED, FAILED, TERMINAL, CANCELED, IDLE }
enum class Freshness { FRESH, STALE, UNKNOWN }
enum class WorkFamily { FEATURE_GOAL, FEATURE_TASK_RUNTIME, FEATURE_VERIFY }
enum class ExecutionScope { WORKFLOW, STANDALONE_PHASE }
enum class Retention(val cliValue: String, val duration: java.time.Duration) {
  SIX_HOURS("6h", java.time.Duration.ofHours(6)), DAY("24h", java.time.Duration.ofHours(24)), WEEK("7d", java.time.Duration.ofDays(7))
}

data class WorkIdentity(val id: String, val repositoryIdentity: String)

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
  val requiresAttention: Boolean = false,
  val displayGroup: WorkSection? = null,
  val displayPriority: Int = 5,
  val exactPauseReason: String? = null,
  val databaseFileObservation: DatabaseFileObservation? = null,
  val repositoryName: String? = null,
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
  val latestActivity: LatestActivity = LatestActivity(),
  val executionHistory: List<ExecutionInstance> = emptyList(),
  val observedAt: Instant? = null,
  val nextCursor: String? = null,
  val pageOffset: Int = 0,
  val cursors: Map<DetailList, String> = emptyMap(),
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
  UNKNOWN("unknown"),
  ;

  companion object {
    fun fromWire(value: String): ProblemCode = entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
  }
}

data class Problem(val code: ProblemCode, val summary: String, val storageFailure: StorageFailure? = null)

enum class HistoryFilter { ALL, GOALS, PHASES }

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

enum class WorkSection { NEEDS_YOU, GOALS, PHASES, HISTORY, RECENT }

val allowedTransitions = listOf("blocked", "failed", "goal_completed", "pause_took_effect", "standalone_phase_finished")
val defaultNotifications = mapOf("blocked" to true, "failed" to true, "goal_completed" to true, "pause_took_effect" to true, "standalone_phase_finished" to false)

fun WorkItem.needsAttention(): Boolean =
  requiresAttention

fun WorkItem.isPhase(): Boolean = displayGroup == WorkSection.PHASES

fun WorkItem.eligibleForControl(): Boolean =
  controlEligible

data class ExecutionInstance(
  val invocationId: String,
  val executionId: String,
  val lifecycleState: String,
  val startedAt: Instant,
  val updatedAt: Instant,
  val finishedAt: Instant?,
  val result: String?,
)

data class SubtaskDependency(val subtaskId: Int, val dependency: WorkDependency)

data class RunOptions(
  val canCancel: Boolean,
  val canLaunch: Boolean,
  val launchLabel: String,
  val agentId: String?,
  val agentChoices: List<String>,
  val unavailableReason: String?,
)
