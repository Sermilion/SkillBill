package dev.skillbill.desktop.core.domain.model

import kotlin.time.Instant

/** The runtime's lifecycle verdict, one to one by name; [Idle] exists because the runtime has seven states. */
enum class Lifecycle { Active, Paused, Blocked, Failed, Terminal, Canceled, Idle }

/** The runtime's display group verdict; null on an item means the runtime placed it in none. */
enum class WorkDisplayGroup { Goals, Phases, Operations }

/** What kind of work a row is, mirrored from [WorkDisplayGroup] and never inferred from family or scope. */
enum class WorkKind { Goal, Phase, Operation }

/** The runtime's live section verdict; null for finished work. */
enum class WorkLiveSection { NeedsYou, Goals, Phases, Operations }

data class WorkProgress(val done: Int, val total: Int)

enum class CompletionSource { StandaloneFinishedAt, WorkflowCompletion, GoalCompletion, GoalStateEntry, EstimatedGoalStateEntry }

enum class PhaseExecutionKind { Pass, SemanticLoop, GateRun, BoundedEdge, Attempt }

data class PhaseExecution(val phaseId: String, val kind: PhaseExecutionKind, val count: Int, val total: Int?, val criterionRef: String?)

data class DatabaseFileIdentity(val path: String, val fileKey: String)

/** The facts a control request rebuilds its runtime target from, held as plain values. */
data class WorkControlIdentity(
  val sessionId: Long,
  val workflowId: String?,
  val invocationId: String?,
  val repositoryIdentity: String,
  val executionId: String?,
  val statusStoreId: String?,
  val runSequence: String?,
  val statusRevision: String?,
  val phaseId: String?,
  val updatedAt: Instant,
  val controlLeaseOwner: String?,
  val controlLeaseGeneration: Long?,
  val storeId: String?,
  val storeRevision: Long?,
  val databaseFile: DatabaseFileIdentity?,
)

/**
 * One board row or detail header. Every field is a runtime verdict or a fact copied from the runtime's read; the app sorts,
 * groups and judges nothing on it. [agentId] is the runtime's executing agent on board rows (a goal's active agent, a
 * standalone run's launch agent, or a runtime's running or finalizing agent), null when none was recorded. It is display
 * only; launches use [RunOptions.agentId].
 */
data class WorkItem(
  val id: String,
  val kind: WorkKind?,
  val issueKey: String?,
  val title: String,
  val summary: String,
  val lifecycle: Lifecycle,
  val planningPrepared: Boolean,
  val stalledSince: Instant?,
  val pauseRequested: Boolean,
  val fresh: Boolean,
  val stepLabel: String,
  val phaseExecution: PhaseExecution?,
  val phaseExecutionLabel: String?,
  val progress: WorkProgress?,
  val planningProgress: WorkProgress?,
  val activeDurationMs: Long?,
  val activeDurationAsOf: Instant?,
  val startedAt: Instant?,
  val finishedAt: Instant?,
  val model: String?,
  val effort: String?,
  val agentId: String?,
  val branch: String?,
  val repositoryName: String,
  val repositoryRoot: String,
  val liveSection: WorkLiveSection?,
  val displayGroup: WorkDisplayGroup?,
  val displayPriority: Int,
  val requiresAttention: Boolean,
  val controlEligible: Boolean,
  val pauseEligible: Boolean,
  val running: Boolean,
  val recentlyFinishedPhase: Boolean,
  val doneWithinDay: Boolean,
  val freshnessDeadline: Instant?,
  val membershipDeadline: Instant?,
  val exactPauseReason: String?,
  val awaitsOperatorDecision: Boolean,
  val currentSubtaskId: String?,
  val reason: String?,
  val control: WorkControlIdentity,
  val completionSource: CompletionSource? = null,
  val qaVerdict: QaVerdict? = null,
  val workflowQaOutcome: WorkQaOutcome? = null,
  val standaloneExecution: Boolean = false,
)
