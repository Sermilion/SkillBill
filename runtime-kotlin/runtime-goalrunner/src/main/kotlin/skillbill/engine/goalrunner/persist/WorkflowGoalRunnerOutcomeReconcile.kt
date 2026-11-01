package skillbill.engine.goalrunner.persist

import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.diagnostics.block.BlockIncidentObservation
import skillbill.engine.goalrunner.execution.support.authoritativeOutcomesBySubtask
import skillbill.engine.goalrunner.execution.support.selectAuthoritativeCandidate
import skillbill.engine.goalrunner.execution.support.staleRunningReason
import skillbill.engine.goalrunner.model.GoalContinuationCandidate
import skillbill.engine.goalrunner.model.GoalRunnerBlockWrite
import skillbill.engine.goalrunner.model.GoalRunnerReconcileGate
import skillbill.engine.goalrunner.model.GoalSubtaskIdentity
import skillbill.engine.goalrunner.model.StaleRunningCandidatesBlockRequest
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.catchingNonCancellation
import skillbill.error.core.failureCodeLabel
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.STALENESS_EVIDENCE_WINDOW
import skillbill.goalrunner.ledger.declaredProgressEventFrom
import skillbill.goalrunner.model.GoalContinuation
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.terminalOutcomeFor
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalobservability.goalObservabilityLatestEventFromArtifacts
import skillbill.workflow.taskruntime.model.persistence.goalContinuation
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal data class GoalOutcomeReconciliationRequest(
  val issueKey: String,
  val parentWorkflowId: String,
  val activeWorkflowIds: Set<String>,
  val gate: GoalRunnerReconcileGate,
  val repoRoot: Path?,
)

internal data class GoalOutcomeReconciliationResult(
  val outcomes: Map<Int, GoalRunnerStoredOutcome>,
  val blockObservations: List<BlockIncidentObservation>,
)

internal class WorkflowGoalRunnerOutcomeReconcile(
  private val engine: WorkflowEngine,
  private val gitOperations: WorkflowGitOperations,
  private val blockWrites: WorkflowGoalRunnerBlockWrites,
  private val terminalPersistence: WorkflowGoalRunnerOutcomeTerminalPersistence,
  private val progressRecording: WorkflowGoalRunnerProgressRecording,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun reconcileAuthoritativeOutcomesInTransaction(
    unitOfWork: UnitOfWork,
    request: GoalOutcomeReconciliationRequest,
  ): GoalOutcomeReconciliationResult {
    val normalizedIssueKey = request.issueKey.trim()
    val activeSet = request.activeWorkflowIds.map(String::trim).filter(String::isNotBlank).toSet()
    displaceStaleBlockedOutcomes(unitOfWork, normalizedIssueKey, request.parentWorkflowId)
    val initialCandidates =
      loadContinuationCandidates(
        unitOfWork.workflowStates,
        normalizedIssueKey,
        request.parentWorkflowId,
        request.repoRoot,
      )
    persistMeasuredCompletions(unitOfWork, initialCandidates, request.repoRoot, request.parentWorkflowId)
    val initialAuthoritative = initialCandidates.authoritativeOutcomesBySubtask()
    val blockObservations =
      blockStaleRunningCandidates(
        StaleRunningCandidatesBlockRequest(
          unitOfWork = unitOfWork,
          normalizedIssueKey = normalizedIssueKey,
          candidates = initialCandidates,
          initialAuthoritative = initialAuthoritative,
          activeSet = activeSet,
          gate = request.gate,
        ),
      )
    val outcomes =
      loadContinuationCandidates(
        unitOfWork.workflowStates,
        normalizedIssueKey,
        request.parentWorkflowId,
        request.repoRoot,
      )
        .authoritativeOutcomesBySubtask()
    return GoalOutcomeReconciliationResult(outcomes, blockObservations)
  }

  private fun displaceStaleBlockedOutcomes(
    unitOfWork: UnitOfWork,
    issueKey: String,
    parentWorkflowId: String,
  ) {
    loadContinuationCandidates(unitOfWork.workflowStates, issueKey, parentWorkflowId, repoRoot = null)
      .forEach { candidate ->
        terminalPersistence.displaceStaleBlockedContinuationOutcomeIfPresent(
          unitOfWork.workflowStates,
          candidate.snapshot.workflowId,
          candidate.goalContinuation.issueKey,
          candidate.goalContinuation.subtaskId,
        )
      }
  }

  private fun persistMeasuredCompletions(
    unitOfWork: UnitOfWork,
    candidates: List<GoalContinuationCandidate>,
    repoRoot: Path?,
    parentWorkflowId: String,
  ) {
    if (repoRoot == null) return
    candidates
      .filter { candidate -> candidate.outcome?.status == GoalRunnerTerminalStatus.COMPLETE }
      .forEach { candidate ->
        if (!progressRecording.isCurrentGoalChild(
            unitOfWork,
            parentWorkflowId,
            candidate.goalContinuation.subtaskId,
            candidate.snapshot.workflowId,
          )
        ) {
          return@forEach
        }
        requireHistoryDraft(unitOfWork, candidate, parentWorkflowId, candidate.goalContinuation.subtaskId)
        terminalPersistence.persistMeasuredCompletion(
          unitOfWork,
          parentWorkflowId,
          GoalSubtaskIdentity(
            candidate.snapshot.workflowId,
            candidate.goalContinuation.issueKey,
            candidate.goalContinuation.subtaskId,
          ),
          requireNotNull(candidate.outcome),
        )
      }
  }

  private fun blockStaleRunningCandidates(request: StaleRunningCandidatesBlockRequest): List<BlockIncidentObservation> =
    request.candidates
      .filter { candidate ->
        isStaleRunningCandidate(candidate, request.initialAuthoritative, request.activeSet, request.gate)
      }
      .map { stale ->
        val authoritative = request.initialAuthoritative[stale.goalContinuation.subtaskId]
        val blockedReason =
          staleRunningReason(
            staleWorkflowId = stale.snapshot.workflowId,
            issueKey = request.normalizedIssueKey,
            subtaskId = stale.goalContinuation.subtaskId,
            authoritative = authoritative,
          )
        blockWrites.markBlocked(
          GoalRunnerBlockWrite(
            family = stale.family,
            record = stale.snapshot,
            blockedReason = blockedReason,
            lastResumableStep = stale.snapshot.currentStepId,
            supervisionEvent = null,
          ),
          request.unitOfWork.workflowStates,
          request.unitOfWork.blockIncidents,
        ).observation
      }

  private fun isStaleRunningCandidate(
    candidate: GoalContinuationCandidate,
    initialAuthoritative: Map<Int, GoalRunnerStoredOutcome>,
    activeSet: Set<String>,
    gate: GoalRunnerReconcileGate,
  ): Boolean {
    if (candidate.snapshot.workflowStatus != WorkflowStatus.RUNNING) return false
    if (candidate.outcome?.status == GoalRunnerTerminalStatus.COMPLETE) return false
    val authoritative = initialAuthoritative[candidate.goalContinuation.subtaskId]
    val inactive = candidate.snapshot.workflowId !in activeSet
    val supersededByAuthoritative =
      authoritative?.status == GoalRunnerTerminalStatus.COMPLETE &&
        authoritative.workflowId != candidate.snapshot.workflowId
    val staleByInactivity =
      if (gate.requireStalenessEvidence) {
        inactive && candidateIsStale(candidate)
      } else {
        gate.allowInactiveReconciliation && inactive
      }
    return staleByInactivity || supersededByAuthoritative
  }

  private fun loadContinuationCandidates(
    workflowStates: WorkflowStateRepository,
    issueKey: String,
    parentWorkflowId: String,
    repoRoot: Path?,
  ): List<GoalContinuationCandidate> =
    buildList {
      val candidates = linkedMapOf<String, WorkflowStateSnapshot>()
      var offset = 0
      do {
        val page =
          workflowStates.readGoalStatusChildPage(
            issueKey.trim(),
            offset,
            WorkPaginationLimits.MAX_PAGE_SIZE,
            parentWorkflowId = parentWorkflowId,
          )
        page.forEach { projected ->
          val snapshot = workflowStates.getFeatureTaskWorkflow(projected.workflowId)?.toSnapshot() ?: return@forEach
          candidates[snapshot.workflowId] = snapshot
        }
        offset += page.size
      } while (page.size == WorkPaginationLimits.MAX_PAGE_SIZE)
      val currentChildren = currentChildren(workflowStates, parentWorkflowId)
      currentChildren.keys.forEach { childId ->
        val snapshot =
          workflowStates.getFeatureTaskWorkflow(childId)?.toSnapshot()
            ?: throw SkillBillRuntimeException(
              WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
              "Current goal child $childId is missing during outcome reconciliation.",
            )
        candidates.putIfAbsent(childId, snapshot)
      }
      candidates.values.forEach { snapshot ->
        engine.snapshotView(WorkflowFamily.TASK_RUNTIME.definition, snapshot)
        val continuation = DurableWorkflowArtifacts.fromMap(snapshot.artifacts).goalContinuation()
        if (continuation == null) {
          if (snapshot.workflowId in currentChildren) {
            throw SkillBillRuntimeException(
              WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
              "Current goal child ${snapshot.workflowId} has no continuation during outcome reconciliation.",
            )
          }
          return@forEach
        }
        currentChildren[snapshot.workflowId]?.let { subtaskId ->
          validateCurrentContinuation(snapshot, continuation, issueKey, parentWorkflowId, subtaskId)
        }
        if (continuation.issueKey != issueKey.trim()) return@forEach
        add(
          GoalContinuationCandidate(
            family = WorkflowFamily.TASK_RUNTIME,
            snapshot = snapshot,
            goalContinuation = continuation,
            outcome =
              terminalOutcomeFor(snapshot, snapshot.artifacts, continuation) {
                repoRoot?.let { root -> gitOperations.headCommitSha(root).measuredCommitSha() }
              }?.withAcceptedBlockReference(snapshot),
          ),
        )
      }
    }

  private fun validateCurrentContinuation(
    snapshot: WorkflowStateSnapshot,
    continuation: GoalContinuation,
    issueKey: String,
    parentWorkflowId: String,
    subtaskId: Int,
  ) {
    val parentId = DurableWorkflowArtifacts.fromMap(snapshot.artifacts).goalContinuationArtifact()?.parentWorkflowId
    val parentMismatch = parentId != null && parentId != parentWorkflowId
    if (continuation.issueKey != issueKey.trim() || continuation.subtaskId != subtaskId || parentMismatch) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
        "Current child ${snapshot.workflowId} has a mismatched parent or subtask continuation.",
      )
    }
  }

  private fun currentChildren(
    workflowStates: WorkflowStateRepository,
    parentWorkflowId: String,
  ): Map<String, Int> {
    val parent =
      workflowStates.getFeatureTaskWorkflow(parentWorkflowId)?.toSnapshot()
        ?: throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
          "Goal parent $parentWorkflowId is missing during outcome reconciliation.",
        )
    val currentSubtasks =
      parent.decompositionRuntime()?.subtasks
        ?: throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
          "Goal parent $parentWorkflowId has no durable manifest during outcome reconciliation.",
        )
    val currentChildEntries = validateCurrentChildren(currentSubtasks, parentWorkflowId)
    return currentChildEntries.toMap()
  }

  private fun validateCurrentChildren(
    currentSubtasks: List<DecompositionSubtask>,
    parentWorkflowId: String,
  ): List<Pair<String, Int>> {
    if (currentSubtasks.any { it.workflowId?.isBlank() == true }) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
        "Goal parent $parentWorkflowId contains a blank child workflow identity.",
      )
    }
    val currentChildEntries = currentSubtasks.mapNotNull { subtask -> subtask.workflowId?.let { it to subtask.id } }
    if (currentChildEntries.map { it.first }.distinct().size != currentChildEntries.size) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
        "Goal parent $parentWorkflowId assigns one child to multiple subtasks.",
      )
    }
    return currentChildEntries
  }

  fun authoritativeOutcomesForSubtasks(
    unitOfWork: UnitOfWork,
    issueKey: String,
    parentWorkflowId: String,
    subtaskIds: Set<Int>?,
  ): Map<Int, GoalRunnerStoredOutcome> {
    val winners = mutableMapOf<Int, GoalContinuationCandidate>()
    var offset = 0
    do {
      val page =
        unitOfWork.workflowStates.readGoalStatusChildPage(
          issueKey.trim(),
          offset,
          WorkPaginationLimits.MAX_PAGE_SIZE,
          subtaskIds,
          parentWorkflowId,
        )
      page.forEach { snapshot ->
        engine.snapshotView(WorkflowFamily.TASK_RUNTIME.definition, snapshot)
        val continuation = DurableWorkflowArtifacts.fromMap(snapshot.artifacts).goalContinuation() ?: return@forEach
        val candidate =
          GoalContinuationCandidate(
            WorkflowFamily.TASK_RUNTIME,
            snapshot,
            continuation,
            terminalOutcomeFor(snapshot, snapshot.artifacts, continuation)?.withAcceptedBlockReference(snapshot),
          )
        if (!progressRecording.isCurrentGoalChild(
            unitOfWork,
            parentWorkflowId,
            continuation.subtaskId,
            snapshot.workflowId,
          )
        ) {
          return@forEach
        }
        if (candidate.outcome?.status == GoalRunnerTerminalStatus.COMPLETE) {
          requireHistoryDraft(unitOfWork, candidate, parentWorkflowId, continuation.subtaskId)
        }
        listOfNotNull(winners[continuation.subtaskId], candidate).selectAuthoritativeCandidate()
          ?.let { winners[continuation.subtaskId] = it }
      }
      offset += page.size
    } while (page.size == WorkPaginationLimits.MAX_PAGE_SIZE)
    addCurrentChildOutcomes(unitOfWork, issueKey, parentWorkflowId, subtaskIds, winners)
    return winners.mapValues { requireNotNull(it.value.outcome) }
  }

  private fun addCurrentChildOutcomes(
    unitOfWork: UnitOfWork,
    issueKey: String,
    parentWorkflowId: String,
    subtaskIds: Set<Int>?,
    winners: MutableMap<Int, GoalContinuationCandidate>,
  ) {
    val parent =
      unitOfWork.workflowStates.getFeatureTaskWorkflow(parentWorkflowId)?.toSnapshot()?.decompositionRuntime()
        ?: throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
          "Goal parent $parentWorkflowId is missing its durable manifest during outcome reconciliation.",
        )
    parent.subtasks
      .filter { subtaskIds == null || it.id in subtaskIds }
      .forEach { subtask ->
        val childId = subtask.workflowId ?: return@forEach
        if (childId.isBlank()) {
          throw SkillBillRuntimeException(
            WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
            "Goal parent $parentWorkflowId contains a blank child workflow identity.",
          )
        }
        val (snapshot, continuation) = currentChildContinuation(unitOfWork, childId)
        validateCurrentContinuation(snapshot, continuation, issueKey, parentWorkflowId, subtask.id)
        val candidate =
          GoalContinuationCandidate(
            WorkflowFamily.TASK_RUNTIME,
            snapshot,
            continuation,
            terminalOutcomeFor(snapshot, snapshot.artifacts, continuation)?.withAcceptedBlockReference(snapshot),
          )
        if (candidate.outcome?.status == GoalRunnerTerminalStatus.COMPLETE) {
          requireHistoryDraft(unitOfWork, candidate, parentWorkflowId, continuation.subtaskId)
        }
        listOfNotNull(winners[continuation.subtaskId], candidate).selectAuthoritativeCandidate()
          ?.let { winners[continuation.subtaskId] = it }
      }
  }

  private fun currentChildContinuation(
    unitOfWork: UnitOfWork,
    childId: String,
  ): Pair<WorkflowStateSnapshot, GoalContinuation> {
    val snapshot =
      unitOfWork.workflowStates.getFeatureTaskWorkflow(childId)?.toSnapshot()
        ?: throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
          "Current goal child $childId is missing during outcome reconciliation.",
        )
    engine.snapshotView(WorkflowFamily.TASK_RUNTIME.definition, snapshot)
    val continuation =
      DurableWorkflowArtifacts.fromMap(snapshot.artifacts).goalContinuation()
        ?: throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
          "Current goal child $childId has no continuation during outcome reconciliation.",
        )
    return snapshot to continuation
  }

  private fun requireHistoryDraft(
    unitOfWork: UnitOfWork,
    candidate: GoalContinuationCandidate,
    parentWorkflowId: String,
    subtaskId: Int,
  ) {
    if (!progressRecording.hasRequiredGoalChildHistoryDraft(
        unitOfWork,
        parentWorkflowId,
        subtaskId,
        candidate.snapshot.workflowId,
      )
    ) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT,
        "Goal child ${candidate.snapshot.workflowId} cannot be recovered as complete " +
          "without its accepted history draft or skip.",
      )
    }
  }

  private fun candidateIsStale(candidate: GoalContinuationCandidate): Boolean =
    catchingNonCancellation {
      candidate.outcome?.status?.let { return@catchingNonCancellation it != GoalRunnerTerminalStatus.COMPLETE }
      val now = clock.instant()
      val window = STALENESS_EVIDENCE_WINDOW
      val liveness = candidateLivenessInstants(candidate)
      val recent = liveness.any { signal -> Duration.between(signal, now).let { !it.isNegative && it <= window } }
      liveness.isNotEmpty() && !recent
    }.onFailure { failure ->
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "seam=goal_continuation_staleness value_expected=classified value_used=not_stale " +
          "failure=${failure.failureCodeLabel() ?: failure::class.simpleName}",
        failure,
      )
    }.getOrDefault(false)

  private fun candidateLivenessInstants(candidate: GoalContinuationCandidate): List<Instant> {
    val artifacts = candidate.snapshot.artifacts
    val declared = declaredProgressEventFrom(artifacts)?.timestamp
    val observed = goalObservabilityLatestEventFromArtifacts(artifacts)?.timestamp
    return listOfNotNull(declared, observed, candidate.snapshot.updatedAt)
  }
}
