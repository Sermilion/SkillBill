package skillbill.engine.goalrunner.execution.core

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.manifest.reconcileGoalManifest
import skillbill.engine.goalrunner.manifest.toResetSnapshot
import skillbill.engine.goalrunner.model.GoalRunnerAcceptRequest
import skillbill.engine.goalrunner.model.GoalRunnerAcceptResult
import skillbill.engine.goalrunner.model.GoalRunnerAcceptanceEvidence
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneOffset

@Inject
class GoalRunnerAcceptanceCoordinator(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val gitOperations: WorkflowGitOperations,
  private val clock: Clock,
) {
  fun accept(request: GoalRunnerAcceptRequest): GoalRunnerAcceptResult {
    val rejection = acceptanceRejection(request)
    if (rejection != null) {
      return GoalRunnerAcceptResult.Rejected(request.issueKey, rejection)
    }
    val loaded = requireNotNull(manifestStore.loadDurableByIssueKey(request.issueKey))
    val repoRoot = requireNotNull(request.repoRoot)
    val resolvedSha =
      when (val evidence = acceptanceEvidence(request, loaded.manifest, repoRoot)) {
        is GoalRunnerAcceptanceEvidence.Rejected -> return rejected(request, evidence.reason)
        is GoalRunnerAcceptanceEvidence.Resolved -> evidence.commitSha
      }
    val acceptance =
      GoalRunnerOutOfBandAcceptance(
        subtaskId = request.subtaskId,
        commitSha = resolvedSha,
        reason = request.reason,
        acceptedAt = clock.instant().atOffset(ZoneOffset.UTC).toString(),
      )
    manifestStore.persistOutOfBandAcceptance(loaded.parentWorkflowId, acceptance)
    val refreshed = manifestStore.loadDurableByIssueKey(request.issueKey) ?: loaded
    val reconciled =
      reconcileGoalManifest(
        manifest = refreshed.manifest,
        authoritativeOutcomes = outcomeStore.authoritativeOutcomes(refreshed.manifest.issueKey),
        acceptances = manifestStore.outOfBandAcceptances(refreshed.parentWorkflowId),
        outcomeStore = outcomeStore,
      )
    val saved = manifestStore.save(refreshed.copy(manifest = reconciled))
    return GoalRunnerAcceptResult.Accepted(
      issueKey = saved.manifest.issueKey,
      parentWorkflowId = saved.parentWorkflowId,
      subtaskId = acceptance.subtaskId,
      commitSha = acceptance.commitSha,
      reason = acceptance.reason,
      acceptedAt = acceptance.acceptedAt,
      after = saved.manifest.toResetSnapshot(),
    )
  }

  private fun acceptanceRejection(request: GoalRunnerAcceptRequest): String? =
    when {
      !request.restoreAfterHardReset ->
        "Out-of-band accept is disabled. Repair or resume the child through the runtime; " +
          "accepting past an incomplete or blocked subtask is not supported. " +
          "Only --restore-after-hard-reset remains for recoveries that hard reset discarded."
      manifestStore.loadDurableByIssueKey(request.issueKey) == null ->
        "No prepared goal exists for '${request.issueKey}'."
      request.repoRoot == null ->
        "A repository root is required to verify the accepted commit."
      else -> null
    }

  private fun rejected(
    request: GoalRunnerAcceptRequest,
    reason: String,
  ): GoalRunnerAcceptResult.Rejected = GoalRunnerAcceptResult.Rejected(request.issueKey, reason)

  private fun acceptanceEvidence(
    request: GoalRunnerAcceptRequest,
    manifest: DecompositionManifest,
    repoRoot: Path,
  ): GoalRunnerAcceptanceEvidence {
    val subtask =
      manifest.subtasks.firstOrNull { it.id == request.subtaskId }
        ?: return GoalRunnerAcceptanceEvidence.Rejected("Subtask ${request.subtaskId} is not part of this goal.")
    acceptanceStateRejection(request, subtask)?.let { reason ->
      return GoalRunnerAcceptanceEvidence.Rejected(reason)
    }
    val unsatisfiedDependencyId = unsatisfiedDependency(manifest, subtask)
    if (unsatisfiedDependencyId != null) {
      return GoalRunnerAcceptanceEvidence.Rejected(
        "Subtask ${request.subtaskId} depends on subtask $unsatisfiedDependencyId, which is not complete or skipped.",
      )
    }
    return resolvedAcceptanceEvidence(request, repoRoot)
  }

  private fun acceptanceStateRejection(
    request: GoalRunnerAcceptRequest,
    subtask: DecompositionSubtask,
  ): String? {
    val clearedByHardReset =
      subtask.status.decompositionStatus() == DecompositionStatus.PENDING &&
        subtask.branch == null &&
        subtask.commitSha == null &&
        subtask.workflowId == null &&
        subtask.blockedReason == null &&
        subtask.lastResumableStep == null
    return when {
      request.restoreAfterHardReset && !clearedByHardReset ->
        "Subtask ${request.subtaskId} is not in the cleared reset state required for acceptance restoration."
      subtask.status.decompositionStatus() == DecompositionStatus.COMPLETE ->
        "Subtask ${request.subtaskId} is already complete."
      else -> null
    }
  }

  private fun resolvedAcceptanceEvidence(
    request: GoalRunnerAcceptRequest,
    repoRoot: Path,
  ): GoalRunnerAcceptanceEvidence {
    val resolved = gitOperations.resolveCommit(repoRoot, request.commitSha)
    val resolvedSha = resolved.value.trim()
    return if (resolved is WorkflowGitOperationResult.Ok && resolvedSha.isNotBlank()) {
      GoalRunnerAcceptanceEvidence.Resolved(resolvedSha)
    } else {
      GoalRunnerAcceptanceEvidence.Rejected(
        resolved.error.takeIf(String::isNotBlank)
          ?: "Commit '${request.commitSha}' could not be resolved in this repository.",
      )
    }
  }

  private fun unsatisfiedDependency(
    manifest: DecompositionManifest,
    subtask: DecompositionSubtask,
  ): Int? {
    val subtasksById = manifest.subtasks.associateBy(DecompositionSubtask::id)
    return subtask.dependencies.firstOrNull { dependency ->
      val dependencySubtask = subtasksById[dependency.subtaskId]
      val satisfied =
        dependencySubtask?.status.decompositionStatus() in
          setOf(
            DecompositionStatus.COMPLETE,
            DecompositionStatus.SKIPPED,
          ) ||
          (dependency.optional && dependency.skipped)
      !satisfied
    }?.subtaskId
  }
}
