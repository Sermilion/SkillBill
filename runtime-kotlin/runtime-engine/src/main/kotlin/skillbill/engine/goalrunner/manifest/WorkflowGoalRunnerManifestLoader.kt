package skillbill.engine.goalrunner.manifest

import skillbill.application.decomposition.resolveDecompositionManifest
import skillbill.application.workflow.decomposition.findDecomposedParentOrCorruptFallback
import skillbill.application.workflow.decomposition.findDecomposedParentWorkflow
import skillbill.application.workflow.decomposition.requireRuntimeModeForEngineWrite
import skillbill.application.workflow.persist.generateWorkflowId
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.issuekey.normalizeRequiredIssueKey
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.decomposition.withParentStatus
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.decompositionStatus
import java.nio.file.Path
import java.time.Clock
import kotlin.random.Random

internal class WorkflowGoalRunnerManifestLoader(
  private val database: DatabaseSessionFactory,
  private val decompositionManifestValidator: DecompositionManifestValidator,
  private val decompositionManifestStore: DecompositionManifestStore,
  private val engine: WorkflowEngine,
  private val parentProjection: GoalParentProjectionWriter,
  private val clock: Clock,
  private val random: Random,
) {
  fun findProjectedManifest(
    repoRoot: Path,
    issueKey: String,
    recoverPending: Boolean = true,
  ) = resolveDecompositionManifest(
    repoRoot = repoRoot,
    issueKey = issueKey,
    fileStore = decompositionManifestStore,
    validator = decompositionManifestValidator,
    recoverPending = recoverPending,
  )

  fun loadFromWorkflowStore(
    issueKey: String,
    currentProjectedManifest: DecompositionManifest? = null,
  ): GoalRunnerManifestState? =
    database.read { unitOfWork ->
      loadFromWorkflowUnitOfWork(unitOfWork, issueKey, currentProjectedManifest)
    }

  fun loadFromWorkflowStoreIfPresent(
    issueKey: String,
    currentProjectedManifest: DecompositionManifest? = null,
  ): GoalRunnerManifestState? =
    database.readIfPresent { unitOfWork ->
      loadFromWorkflowUnitOfWork(unitOfWork, issueKey, currentProjectedManifest)
    }

  fun loadFromWorkflowUnitOfWork(
    unitOfWork: UnitOfWork,
    issueKey: String,
    currentProjectedManifest: DecompositionManifest?,
  ): GoalRunnerManifestState? {
    val record =
      unitOfWork.workflowStates.findDecomposedParentWorkflow(
        issueKey,
        currentProjectedManifest,
      ) ?: return null
    val snapshot = record.toSnapshot()
    val manifest = snapshot.decompositionRuntime() ?: return null
    return GoalRunnerManifestState(
      parentWorkflowId = snapshot.workflowId,
      dbPath = unitOfWork.dbPath.toString(),
      manifest = manifest,
      controlState = unitOfWork.goalRunnerControls.controlState(snapshot.workflowId),
    )
  }

  fun importFromManifestProjection(manifest: DecompositionManifest): GoalRunnerManifestState? =
    database.transaction { unitOfWork ->
      val existingRecord =
        unitOfWork.workflowStates.findDecomposedParentOrCorruptFallback(
          manifest.issueKey,
          manifest,
        )
      existingRecord?.requireRuntimeModeForEngineWrite()
      val existing = existingRecord?.toSnapshot()
      val base =
        existing ?: engine.openRecord(
          WorkflowFamily.TASK_RUNTIME.definition,
          generateWorkflowId(WorkflowFamily.TASK_RUNTIME.definition.workflowIdPrefix, clock, random),
          WorkflowFamily.TASK_RUNTIME.definition.defaultSessionPrefix,
          "plan",
        )
      val imported =
        engine.updateRecord(
          WorkflowFamily.TASK_RUNTIME.definition,
          base,
          WorkflowUpdateInput(
            workflowStatus = WorkflowStatus.PAUSED,
            currentStepId = "plan",
            stepUpdates =
              if (existing != null) {
                null
              } else {
                WorkflowStepUpdates.from(
                  listOf(
                    mapOf(
                      SharedPayloadKeys.STEP_ID to "preplan",
                      SharedPayloadKeys.STATUS to "completed",
                      "attempt_count" to 1,
                    ),
                    mapOf(
                      SharedPayloadKeys.STEP_ID to "plan",
                      SharedPayloadKeys.STATUS to "completed",
                      "attempt_count" to 1,
                    ),
                  ),
                )
              },
            artifactsPatch = parentProjection.artifacts(manifest, base.artifacts),
            sessionId = base.sessionId.orEmpty(),
            replaceArtifacts = true,
          ),
        )
      unitOfWork.workflowStates.saveRecord(
        WorkflowFamily.TASK_RUNTIME,
        imported.toRecord().copy(issueKey = normalizeRequiredIssueKey(manifest.issueKey)),
      )
      val saved = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, imported.workflowId) ?: imported
      GoalRunnerManifestState(
        parentWorkflowId = saved.workflowId,
        dbPath = unitOfWork.dbPath.toString(),
        manifest = saved.decompositionRuntime() ?: manifest,
        controlState = unitOfWork.goalRunnerControls.controlState(saved.workflowId),
      )
    }

  fun readProjection(
    stored: GoalRunnerManifestState?,
    projected: DecompositionManifest?,
    repoRoot: Path?,
  ): GoalRunnerManifestState? =
    when {
      shouldRefreshFromCompleteProjection(stored, projected) ->
        requireNotNull(stored).copy(
          manifest = requireNotNull(projected),
          repoRoot = repoRoot,
        )
      stored != null -> stored.copy(repoRoot = repoRoot)
      projected != null ->
        GoalRunnerManifestState(
          parentWorkflowId = "",
          dbPath = "",
          manifest = projected,
          repoRoot = repoRoot,
        )
      else -> null
    }

  fun shouldRefreshFromCompleteProjection(
    stored: GoalRunnerManifestState?,
    projected: DecompositionManifest?,
  ): Boolean =
    stored != null &&
      projected != null &&
      projected.isCompleteGoalProjection() &&
      !stored.manifest.isCompleteGoalProjection()
}

internal fun mergeConcurrentGoalProgress(
  persisted: DecompositionManifest,
  incoming: DecompositionManifest,
): DecompositionManifest {
  val persistedById = persisted.subtasks.associateBy { it.id }
  val mergedSubtasks =
    incoming.subtasks.map { candidate ->
      val current = persistedById[candidate.id]
      if (
        current?.status.decompositionStatus() == DecompositionStatus.COMPLETE &&
        candidate.status.decompositionStatus() != DecompositionStatus.COMPLETE
      ) {
        current ?: candidate
      } else {
        candidate
      }
    }
  val merged = incoming.copy(subtasks = mergedSubtasks)
  return if (
    persisted.currentSubtaskIntent.subtaskId > 0 &&
    merged.subtasks.firstOrNull { it.id == persisted.currentSubtaskIntent.subtaskId }
      ?.status.decompositionStatus() == DecompositionStatus.COMPLETE &&
    merged.currentSubtaskIntent.subtaskId == persisted.currentSubtaskIntent.subtaskId
  ) {
    merged.copy(currentSubtaskIntent = persisted.currentSubtaskIntent).withParentStatus()
  } else {
    merged.withParentStatus()
  }
}

private fun DecompositionManifest.isCompleteGoalProjection(): Boolean =
  status.decompositionStatus() == DecompositionStatus.COMPLETE &&
    currentSubtaskIntent.action == "complete" &&
    subtasks.all { subtask ->
      subtask.status.decompositionStatus() in setOf(DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED) &&
        (subtask.status.decompositionStatus() == DecompositionStatus.SKIPPED || !subtask.commitSha.isNullOrBlank())
    }
