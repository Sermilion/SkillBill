package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.domain.model.DatabaseFileIdentity
import dev.skillbill.desktop.core.domain.model.CompletionSource
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.PhaseExecution
import dev.skillbill.desktop.core.domain.model.PhaseExecutionKind
import dev.skillbill.desktop.core.domain.model.WorkControlIdentity
import dev.skillbill.desktop.core.domain.model.WorkBuild
import dev.skillbill.desktop.core.domain.model.WorkBuildPrevious
import dev.skillbill.desktop.core.domain.model.WorkBuildState
import dev.skillbill.desktop.core.domain.model.WorkQaOutcome
import dev.skillbill.desktop.core.domain.model.QaVerdict
import dev.skillbill.desktop.core.domain.model.WorkDisplayGroup
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.GoalWorktree
import dev.skillbill.desktop.core.domain.model.GoalWorktreeState
import dev.skillbill.desktop.core.domain.model.MainCheckoutOutcome
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.model.WorkLiveSection
import dev.skillbill.desktop.core.domain.model.WorkProgress
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.idestatus.model.IdeStatusDisplayGroup
import skillbill.idestatus.model.IdeStatusExecutionScope
import skillbill.idestatus.model.IdeStatusFreshness
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.WorkBoardItem
import skillbill.ports.idestatus.model.WorkBoardCompletionSource
import skillbill.ports.idestatus.model.GoalMainCheckoutOutcome
import skillbill.ports.idestatus.model.GoalWorktreeView
import skillbill.ports.idestatus.model.GoalWorktreeState as RuntimeGoalWorktreeState
import skillbill.ports.idestatus.model.WorkBuildState as RuntimeWorkBuildState
import skillbill.ports.idestatus.model.WorkBuildView
import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.ports.work.model.WorkStoreObservation
import kotlin.time.toKotlinInstant
import kotlin.time.Instant
import skillbill.ports.idestatus.model.WorkLiveSection as RuntimeLiveSection
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Instant as JavaInstant

/** The verdicts a board row and a detail item both carry beside their snapshot. */
internal class ItemVerdicts(
  val repositoryRoot: String,
  val finishedAt: JavaInstant?,
  val liveSection: RuntimeLiveSection?,
  val displayGroup: IdeStatusDisplayGroup?,
  val displayPriority: Int,
  val requiresAttention: Boolean,
  val controlEligible: Boolean,
  val pauseEligible: Boolean,
  val running: Boolean,
  val recentlyFinishedPhase: Boolean,
  val doneWithinDay: Boolean,
  val freshnessDeadline: JavaInstant?,
  val membershipDeadline: JavaInstant?,
  val exactPauseReason: String?,
  val controlLease: GoalRunnerExecutionLease?,
  val observation: WorkStoreObservation?,
  val databaseFile: DatabaseFileObservation?,
  val sessionId: Long,
  val agentId: String? = null,
  val completionSource: WorkBoardCompletionSource? = null,
  val goalWorktree: GoalWorktreeView? = null,
  val build: WorkBuildView? = null,
)

internal fun WorkBoardItem.toWorkItem(sessionId: Long): WorkItem = snapshot.toWorkItem(
  ItemVerdicts(
    repositoryRoot, finishedAt, liveSection, displayGroup, displayPriority, requiresAttention, controlEligible, pauseEligible,
    running, recentlyFinishedPhase, doneWithinDay, freshnessDeadline, membershipDeadline, exactPauseReason, controlLease,
    observation, databaseFileObservation, sessionId, executingAgentId, finishedAtSource, goalWorktree, build,
  ),
)

/** The detail read carries no membership verdicts; those stay on the board row. */
internal fun WorkDetailItem.toWorkItem(sessionId: Long): WorkItem = snapshot.toWorkItem(
  ItemVerdicts(
    repositoryRoot, finishedAt, liveSection, displayGroup, displayPriority, requiresAttention, controlEligible, pauseEligible,
    running, recentlyFinishedPhase = false, doneWithinDay = false, freshnessDeadline, membershipDeadline = null,
    exactPauseReason, controlLease, observation, databaseFileObservation, sessionId,
    completionSource = finishedAtSource, goalWorktree = goalWorktree, build = build,
  ),
)

/** Field-by-field copy; the id rule: a standalone phase is its invocation, anything else its workflow. */
internal fun IdeStatusSnapshot.toWorkItem(verdicts: ItemVerdicts): WorkItem {
  val id = if (execution?.scope == IdeStatusExecutionScope.STANDALONE_PHASE) execution?.invocationId else workflowId ?: execution?.invocationId
  val phase = currentPhaseExecution?.let {
    PhaseExecution(it.phaseId, it.kind.toDomain(), it.count, it.total, it.criterionRef)
  }
  val displayGroup = verdicts.displayGroup?.toDomain()
  return WorkItem(
    id = requireNotNull(id) { "A work item has neither a workflow nor an invocation id." },
    kind = displayGroup?.toKind(),
    issueKey = issueKey,
    title = displayTitle(title, issueKey),
    summary = summary,
    lifecycle = lifecycleState.toDomain(),
    planningPrepared = planning?.sharedPreplanPrepared == true,
    stalledSince = stalledSince?.toKotlinInstant(),
    pauseRequested = pauseRequested == true,
    fresh = freshness == IdeStatusFreshness.FRESH,
    stepLabel = currentStep.label,
    phaseExecution = phase,
    phaseExecutionLabel = phase?.label(),
    progress = progress?.let { WorkProgress(it.completed, it.total) },
    planningProgress = planning?.let { WorkProgress(it.plannedSubtaskCount, it.totalSubtaskCount) },
    activeDurationMs = activeDurationMs,
    activeDurationAsOf = activeDurationAsOf?.toKotlinInstant(),
    startedAt = startedAt?.toKotlinInstant(),
    finishedAt = verdicts.finishedAt?.toKotlinInstant(),
    model = currentModel?.model,
    effort = currentModel?.effort,
    agentId = verdicts.agentId,
    branch = branchCorrelation,
    repositoryName = RepositoryNames.nameOf(verdicts.repositoryRoot),
    repositoryRoot = verdicts.repositoryRoot,
    liveSection = verdicts.liveSection?.toDomain(),
    displayGroup = displayGroup,
    displayPriority = verdicts.displayPriority,
    requiresAttention = verdicts.requiresAttention,
    controlEligible = verdicts.controlEligible,
    pauseEligible = verdicts.pauseEligible,
    running = verdicts.running,
    recentlyFinishedPhase = verdicts.recentlyFinishedPhase,
    doneWithinDay = verdicts.doneWithinDay,
    freshnessDeadline = verdicts.freshnessDeadline?.toKotlinInstant(),
    membershipDeadline = verdicts.membershipDeadline?.toKotlinInstant(),
    exactPauseReason = verdicts.exactPauseReason,
    awaitsOperatorDecision = pauseReason?.awaitsOperatorDecision == true,
    currentSubtaskId = currentSubtask?.id,
    reason = verdicts.exactPauseReason ?: pauseReason?.label ?: planning?.reason,
    control = WorkControlIdentity(
      sessionId = verdicts.sessionId,
      workflowId = workflowId,
      invocationId = execution?.invocationId,
      repositoryIdentity = repositoryIdentity,
      executionId = execution?.executionId,
      statusStoreId = execution?.statusStoreId,
      runSequence = execution?.runSequence,
      statusRevision = execution?.statusRevision,
      phaseId = execution?.phaseId,
      updatedAt = updatedAt.toKotlinInstant(),
      controlLeaseOwner = verdicts.controlLease?.ownerToken,
      controlLeaseGeneration = verdicts.controlLease?.generation,
      storeId = verdicts.observation?.storeId,
      storeRevision = verdicts.observation?.revision,
      databaseFile = verdicts.databaseFile?.let { DatabaseFileIdentity(it.path.toString(), it.fileKey) },
    ),
    completionSource = verdicts.completionSource?.toDomain(),
    qaVerdict = execution?.qaOutcome?.toDomain(),
    workflowQaOutcome = workflowQaOutcome?.toDomain(),
    standaloneExecution = execution?.scope == IdeStatusExecutionScope.STANDALONE_PHASE,
    goalWorktree = verdicts.goalWorktree?.toDomainWorktree(),
    build = verdicts.build?.toDomain(),
    primaryRepositoryRoot = primaryRepositoryRoot?.takeIf(String::isNotBlank),
    runKindLabel =
      if (workflowFamily == IdeStatusWorkflowFamily.FEATURE_VERIFY) {
        "verify"
      } else {
        execution?.phaseId?.takeIf(String::isNotBlank)
      },
  )
}

internal fun WorkBuildView.toDomain(): WorkBuild = WorkBuild(
  state = state.toDomain(),
  command = command,
  shortCommand = shortCommand,
  tool = tool,
  projectKind = projectKind,
  pid = pid,
  startedAt = startedAt.toKotlinInstant(),
  totalElapsedMs = totalElapsedMs,
  totalElapsedAsOf = totalElapsedAsOf?.toKotlinInstant(),
  elapsedMs = elapsedMs,
  elapsedAsOf = elapsedAsOf?.toKotlinInstant(),
  usualMs = usualMs,
  exitCode = exitCode,
  finishedAt = finishedAt?.toKotlinInstant(),
  previous = previous?.let { WorkBuildPrevious(it.state.toDomain(), it.durationMs, it.finishedAt.toKotlinInstant()) },
)

private fun RuntimeWorkBuildState.toDomain(): WorkBuildState = when (this) {
  RuntimeWorkBuildState.RUNNING -> WorkBuildState.Running
  RuntimeWorkBuildState.PASSED -> WorkBuildState.Passed
  RuntimeWorkBuildState.FAILED -> WorkBuildState.Failed
  RuntimeWorkBuildState.FINISHED -> WorkBuildState.Finished
}

internal fun GoalMainCheckoutOutcome.toDomain(): MainCheckoutOutcome = when (this) {
  GoalMainCheckoutOutcome.CHECKED_OUT -> MainCheckoutOutcome.CheckedOut
  GoalMainCheckoutOutcome.SKIPPED_UNCOMMITTED_CHANGES -> MainCheckoutOutcome.SkippedUncommittedChanges
  GoalMainCheckoutOutcome.FAILED -> MainCheckoutOutcome.Failed
}

/** A worktree the runtime still reports as `ACTIVE` or `RETAINED` but whose directory is gone counts as removed. */
internal fun GoalWorktreeView.toDomainWorktree(): GoalWorktree {
  val mapped = when (state) {
    RuntimeGoalWorktreeState.ACTIVE -> if (directoryExists(path)) GoalWorktreeState.Active else GoalWorktreeState.Removed
    RuntimeGoalWorktreeState.RETAINED -> if (directoryExists(path)) GoalWorktreeState.Retained else GoalWorktreeState.Removed
    RuntimeGoalWorktreeState.REMOVED -> GoalWorktreeState.Removed
  }
  return GoalWorktree(path, mapped, retentionReason, retentionDetail, mainCheckoutOutcome?.toDomain(), mainCheckoutDetail)
}

private fun directoryExists(path: String): Boolean =
  path.isNotBlank() && try {
    Files.isDirectory(Path.of(path))
  } catch (_: InvalidPathException) {
    false
  }

private fun WorkBoardCompletionSource.toDomain(): CompletionSource = when (this) {
  WorkBoardCompletionSource.STANDALONE_FINISHED_AT -> CompletionSource.StandaloneFinishedAt
  WorkBoardCompletionSource.WORKFLOW_COMPLETION -> CompletionSource.WorkflowCompletion
  WorkBoardCompletionSource.GOAL_COMPLETION -> CompletionSource.GoalCompletion
  WorkBoardCompletionSource.GOAL_STATE_ENTRY -> CompletionSource.GoalStateEntry
  WorkBoardCompletionSource.ESTIMATED_GOAL_STATE_ENTRY -> CompletionSource.EstimatedGoalStateEntry
}

private fun skillbill.ports.idestatus.model.StandaloneQaOutcome.toDomain(): QaVerdict =
  QaVerdict.valueOf(name.lowercase().replaceFirstChar(Char::uppercase))

private fun skillbill.ports.idestatus.model.IdeStatusWorkflowQaOutcome.toDomain(): WorkQaOutcome? = try {
  WorkQaOutcome(outcome.toDomain(), reason, failureKind, Instant.parse(recordedAt))
} catch (_: IllegalArgumentException) {
  null
}

/** HANDOFF 9.2's wording ("pass 2", "gate run 2"), extended to the other kinds. */
private fun PhaseExecution.label(): String = when (kind) {
  PhaseExecutionKind.Pass -> "pass $count"
  PhaseExecutionKind.SemanticLoop -> "loop $count"
  PhaseExecutionKind.GateRun -> "gate run $count"
  PhaseExecutionKind.BoundedEdge -> total?.let { "$count/$it" } ?: "edge $count"
  PhaseExecutionKind.Attempt -> if (phaseId in acceptanceAuditPhases) "repair attempt $count" else "run $count"
}

private val acceptanceAuditPhases = setOf("audit", "audit_plan_fix", "audit_implement_fix")

// Exhaustive on purpose: a runtime constant without a desktop counterpart must fail the build, not a read.
internal fun IdeStatusLifecycleState.toDomain(): Lifecycle = when (this) {
  IdeStatusLifecycleState.ACTIVE -> Lifecycle.Active
  IdeStatusLifecycleState.PAUSED -> Lifecycle.Paused
  IdeStatusLifecycleState.BLOCKED -> Lifecycle.Blocked
  IdeStatusLifecycleState.FAILED -> Lifecycle.Failed
  IdeStatusLifecycleState.TERMINAL -> Lifecycle.Terminal
  IdeStatusLifecycleState.CANCELED -> Lifecycle.Canceled
  IdeStatusLifecycleState.IDLE -> Lifecycle.Idle
}

private fun IdeStatusDisplayGroup.toDomain(): WorkDisplayGroup = when (this) {
  IdeStatusDisplayGroup.GOALS -> WorkDisplayGroup.Goals
  IdeStatusDisplayGroup.PHASES -> WorkDisplayGroup.Phases
  IdeStatusDisplayGroup.OPERATIONS -> WorkDisplayGroup.Operations
}

private fun WorkDisplayGroup.toKind(): WorkKind = when (this) {
  WorkDisplayGroup.Goals -> WorkKind.Goal
  WorkDisplayGroup.Phases -> WorkKind.Phase
  WorkDisplayGroup.Operations -> WorkKind.Operation
}

private fun RuntimeLiveSection.toDomain(): WorkLiveSection = when (this) {
  RuntimeLiveSection.NEEDS_YOU -> WorkLiveSection.NeedsYou
  RuntimeLiveSection.GOALS -> WorkLiveSection.Goals
  RuntimeLiveSection.PHASES -> WorkLiveSection.Phases
  RuntimeLiveSection.OPERATIONS -> WorkLiveSection.Operations
}

private fun IdeStatusCurrentPhaseExecutionKind.toDomain(): PhaseExecutionKind = when (this) {
  IdeStatusCurrentPhaseExecutionKind.PASS -> PhaseExecutionKind.Pass
  IdeStatusCurrentPhaseExecutionKind.SEMANTIC_LOOP -> PhaseExecutionKind.SemanticLoop
  IdeStatusCurrentPhaseExecutionKind.GATE_RUN -> PhaseExecutionKind.GateRun
  IdeStatusCurrentPhaseExecutionKind.BOUNDED_EDGE -> PhaseExecutionKind.BoundedEdge
  IdeStatusCurrentPhaseExecutionKind.ATTEMPT -> PhaseExecutionKind.Attempt
}
