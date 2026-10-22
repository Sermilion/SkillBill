package skillbill.engine.goalrunner.persist

import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.engine.diagnostics.block.BlockIncidentObservation
import skillbill.engine.goalrunner.execution.support.authoritativeOutcomesBySubtask
import skillbill.engine.goalrunner.execution.support.selectAuthoritativeCandidate
import skillbill.engine.goalrunner.execution.support.staleRunningReason
import skillbill.engine.goalrunner.model.GoalContinuationCandidate
import skillbill.engine.goalrunner.model.GoalRunnerBlockWrite
import skillbill.engine.goalrunner.model.GoalRunnerReconcileGate
import skillbill.engine.goalrunner.model.StaleRunningCandidatesBlockRequest
import skillbill.goalrunner.STALENESS_EVIDENCE_WINDOW
import skillbill.goalrunner.ledger.declaredProgressEventFrom
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.terminalOutcomeFor
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalobservability.goalObservabilityLatestEventFromArtifacts
import skillbill.workflow.taskruntime.model.persistence.goalContinuation
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
  private val clock: Clock,
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
    persistMeasuredCompletions(unitOfWork, initialCandidates, request.repoRoot)
    val initialAuthoritative = initialCandidates.authoritativeOutcomesBySubtask()
    val blockObservations = blockStaleRunningCandidates(
      StaleRunningCandidatesBlockRequest(
        unitOfWork = unitOfWork,
        normalizedIssueKey = normalizedIssueKey,
        candidates = initialCandidates,
        initialAuthoritative = initialAuthoritative,
        activeSet = activeSet,
        gate = request.gate,
      ),
    )
    val outcomes = loadContinuationCandidates(
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
  ) {
    if (repoRoot == null) return
    candidates
      .filter { candidate -> candidate.outcome?.status == GoalRunnerTerminalStatus.COMPLETE }
      .forEach { candidate ->
        terminalPersistence.persistMeasuredCompletion(
          unitOfWork.workflowStates,
          candidate.snapshot.workflowId,
          candidate.goalContinuation.issueKey,
          candidate.goalContinuation.subtaskId,
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
          engine.snapshotView(WorkflowFamily.TASK_RUNTIME.definition, snapshot)
          val continuation = DurableWorkflowArtifacts.fromMap(snapshot.artifacts).goalContinuation() ?: return@forEach
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
        offset += page.size
      } while (page.size == WorkPaginationLimits.MAX_PAGE_SIZE)
    }

  fun authoritativeOutcomesForSubtasks(
    workflowStates: WorkflowStateRepository,
    issueKey: String,
    parentWorkflowId: String,
    subtaskIds: Set<Int>?,
  ): Map<Int, GoalRunnerStoredOutcome> {
    val winners = mutableMapOf<Int, GoalContinuationCandidate>()
    var offset = 0
    do {
      val page =
        workflowStates.readGoalStatusChildPage(
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
        listOfNotNull(winners[continuation.subtaskId], candidate).selectAuthoritativeCandidate()
          ?.let { winners[continuation.subtaskId] = it }
      }
      offset += page.size
    } while (page.size == WorkPaginationLimits.MAX_PAGE_SIZE)
    return winners.mapValues { requireNotNull(it.value.outcome) }
  }

  private fun candidateIsStale(candidate: GoalContinuationCandidate): Boolean =
    runCatching {
      candidate.outcome?.status?.let { return@runCatching it != GoalRunnerTerminalStatus.COMPLETE }
      val now = clock.instant()
      val window = STALENESS_EVIDENCE_WINDOW
      val liveness = candidateLivenessInstants(candidate)
      val recent = liveness.any { signal -> Duration.between(signal, now).let { !it.isNegative && it <= window } }
      liveness.isNotEmpty() && !recent
    }.getOrDefault(false)

  private fun candidateLivenessInstants(candidate: GoalContinuationCandidate): List<Instant> {
    val artifacts = candidate.snapshot.artifacts
    val declared = declaredProgressEventFrom(artifacts)?.timestamp
    val observed = goalObservabilityLatestEventFromArtifacts(artifacts)?.timestamp
    return listOfNotNull(declared, observed, candidate.snapshot.updatedAt)
  }
}
