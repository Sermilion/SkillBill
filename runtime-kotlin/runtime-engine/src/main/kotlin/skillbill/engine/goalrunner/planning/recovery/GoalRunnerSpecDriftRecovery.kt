package skillbill.engine.goalrunner.planning.recovery

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.engine.goalplanning.GoalPlanningPreparationProjectionGate
import skillbill.engine.goalrunner.goalRepositoryIdentity
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerReplanRequest
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.planning.outcome.resolvedSubSpecPath
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.text.sha256HexUtf8
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import java.nio.file.Path

@Inject
class GoalRunnerSpecDriftRecovery(
  private val checkpoint: GoalPlanningPreparationCheckpoint,
  private val manifestStore: GoalRunnerManifestStore,
  private val fileStore: DecompositionManifestStore,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val statusService: GoalRunnerStatusService,
  envelopeValidator: FeatureTaskRuntimeWireArtifactValidator,
  private val diagnostics: RuntimeDiagnostics,
) {
  private val projectionGate = GoalPlanningPreparationProjectionGate(envelopeValidator)

  fun refresh(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalRunnerManifestState {
    val identity =
      GoalPlanningIdentity(
        state.parentWorkflowId,
        state.manifest.issueKey.trim().uppercase(),
        goalRepositoryIdentity(request.repoRoot, repositoryEnclosingRootPort),
      )
    val drift = findDrift(state, request, identity) ?: return state
    val result =
      requireNotNull(
        statusService.replan(
          GoalRunnerReplanRequest(
            issueKey = request.issueKey,
            subtaskId = drift.subtaskId,
            repoRoot = request.repoRoot,
            includeSharedPreplan = true,
          ),
        ),
      ) { "Goal '${request.issueKey}' disappeared during spec drift recovery." }
    diagnostics.warning(
      "seam=goal_spec_drift_recovery value_expected=current_spec_hash value_used=scoped_replan " +
        "issue_key=${request.issueKey} subtask_id=${drift.subtaskId} " +
        "previous_hash=${drift.previousHash} current_hash=${drift.currentHash} " +
        "cascaded_subtask_ids=${result.cascadedPlanSubtaskIds.joinToString(",")}",
    )
    return requireNotNull(manifestStore.loadDurableByIssueKey(request.issueKey)) {
      "Goal '${request.issueKey}' disappeared after spec drift recovery."
    }.copy(repoRoot = request.repoRoot)
  }

  private fun findDrift(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    identity: GoalPlanningIdentity,
  ): SpecDrift? {
    val canonicalRepository = repositoryEnclosingRootPort.canonicalPath(request.repoRoot)
    val terminal = setOf(DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED)
    return state.manifest.subtasks
      .filter { it.status.decompositionStatus() !in terminal }
      .firstNotNullOfOrNull { subtask ->
        val path =
          resolvedSubSpecPath(canonicalRepository, subtask.specPath, repositoryEnclosingRootPort)
            ?: return@firstNotNullOfOrNull null
        val governedPath = canonicalRepository.relativize(path).joinToString("/")
        val stored =
          checkpoint.findStoredSubtaskPlan(identity, subtask.id, governedPath)
            ?: return@firstNotNullOfOrNull null
        detectDrift(stored, path)
      }
  }

  private fun detectDrift(
    stored: GoalSubtaskPlanCheckpoint,
    path: Path,
  ): SpecDrift? {
    projectionGate.validateSubtaskPlan(stored)
    if (!fileStore.isRegularFile(path)) return null
    val currentHash = sha256HexUtf8(fileStore.readText(path))
    if (stored.subSpecHash == currentHash) return null
    return SpecDrift(stored.subtaskId, stored.subSpecHash, currentHash)
  }

  private data class SpecDrift(
    val subtaskId: Int,
    val previousHash: String,
    val currentHash: String,
  )
}
