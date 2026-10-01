package skillbill.engine.goalrunner.persist

import skillbill.engine.goalrunner.execution.support.authoritativeOutcomesBySubtask
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
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalobservability.goalObservabilityLatestEventFromArtifacts
import skillbill.workflow.taskruntime.model.persistence.goalContinuation
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal class WorkflowGoalRunnerOutcomeReconcile(
  private val engine: WorkflowEngine,
  private val gitOperations: WorkflowGitOperations,
  private val blockWrites: WorkflowGoalRunnerBlockWrites,
  private val terminalPersistence: WorkflowGoalRunnerOutcomeTerminalPersistence,
  private val clock: Clock,
) {
  fun reconcileAuthoritativeOutcomesInTransaction(
    unitOfWork: UnitOfWork,
    issueKey: String,
    activeWorkflowIds: Set<String>,
    gate: GoalRunnerReconcileGate,
    repoRoot: Path?,
  ): Map<Int, GoalRunnerStoredOutcome> {
    val normalizedIssueKey = issueKey.trim()
    val activeSet = activeWorkflowIds.map(String::trim).filter(String::isNotBlank).toSet()
    displaceStaleBlockedOutcomes(unitOfWork, normalizedIssueKey)
    val initialCandidates = loadContinuationCandidates(unitOfWork.workflowStates, normalizedIssueKey, repoRoot)
    persistMeasuredCompletions(unitOfWork, initialCandidates, repoRoot)
    val initialAuthoritative = initialCandidates.authoritativeOutcomesBySubtask()
    blockStaleRunningCandidates(
      StaleRunningCandidatesBlockRequest(
        unitOfWork = unitOfWork,
        normalizedIssueKey = normalizedIssueKey,
        candidates = initialCandidates,
        initialAuthoritative = initialAuthoritative,
        activeSet = activeSet,
        gate = gate,
      ),
    )
    return loadContinuationCandidates(unitOfWork.workflowStates, normalizedIssueKey, repoRoot)
      .authoritativeOutcomesBySubtask()
  }

  private fun displaceStaleBlockedOutcomes(
    unitOfWork: UnitOfWork,
    issueKey: String,
  ) {
    loadContinuationCandidates(unitOfWork.workflowStates, issueKey, repoRoot = null)
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

  private fun blockStaleRunningCandidates(request: StaleRunningCandidatesBlockRequest) {
    request.candidates
      .filter { candidate ->
        isStaleRunningCandidate(candidate, request.initialAuthoritative, request.activeSet, request.gate)
      }
      .forEach { stale ->
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
            workflowStates = request.unitOfWork.workflowStates,
            supervisionEvent = null,
          ),
        )
      }
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

  fun loadContinuationCandidates(
    workflowStates: WorkflowStateRepository,
    issueKey: String,
    repoRoot: Path? = null,
  ): List<GoalContinuationCandidate> =
    listOf(WorkflowFamily.TASK_RUNTIME).flatMap { family ->
      workflowStates.list(family, Int.MAX_VALUE).mapNotNull { snapshot ->
        engine.snapshotView(family.definition, snapshot)
        val artifacts = snapshot.artifacts
        val goalContinuation = DurableWorkflowArtifacts.fromMap(artifacts).goalContinuation() ?: return@mapNotNull null
        if (goalContinuation.issueKey != issueKey) {
          return@mapNotNull null
        }
        GoalContinuationCandidate(
          family = family,
          snapshot = snapshot,
          goalContinuation = goalContinuation,
          outcome =
            terminalOutcomeFor(snapshot, artifacts, goalContinuation) {
              repoRoot?.let { root -> gitOperations.headCommitSha(root).measuredCommitSha() }
            },
        )
      }
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
