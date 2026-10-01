package skillbill.engine.goalrunner.manifest

import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.clearDecompositionManifestProjectionFailure
import skillbill.application.decomposition.persistDecompositionManifestProjectionFailure
import skillbill.application.workflow.decomposition.findDecomposedParentWorkflow
import skillbill.application.workflow.decomposition.requireRuntimeModeForEngineWrite
import skillbill.contracts.issuekey.normalizeRequiredIssueKey
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.status.reconcileControlStateForManifest
import skillbill.model.RepositoryRoot
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.decomposition.runtime.model.DecompositionManifestProjectionOutcome
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowUpdateInput

internal data class SavedManifestProjection(
  internal val state: GoalRunnerManifestState,
  internal val projectionArtifacts: DurableWorkflowArtifacts,
)

internal class WorkflowGoalRunnerManifestProjectionPersistence(
  private val database: DatabaseSessionFactory,
  private val engine: WorkflowEngine,
  private val parentProjection: GoalParentProjectionWriter,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val decompositionManifestWriter: DecompositionManifestWriter,
  private val repositoryRoot: RepositoryRoot,
  private val decompositionManifestValidator: DecompositionManifestValidator,
  private val decompositionManifestStore: DecompositionManifestStore,
) {
  fun save(state: GoalRunnerManifestState): SavedManifestProjection =
    database.transaction { unitOfWork -> saveInTransaction(unitOfWork, state) }

  fun saveInTransaction(
    unitOfWork: UnitOfWork,
    state: GoalRunnerManifestState,
    clearOutOfBandAcceptances: Boolean = false,
    mergeConcurrentProgress: Boolean = true,
  ): SavedManifestProjection {
    val existingRecord =
      unitOfWork.workflowStates.getFeatureTaskWorkflow(state.parentWorkflowId)
        ?: unitOfWork.workflowStates.findDecomposedParentWorkflow(
          state.manifest.issueKey,
        )
        ?: error("Unknown decomposed parent workflow '${state.parentWorkflowId}'.")
    existingRecord.requireRuntimeModeForEngineWrite()
    val existingSnapshot = existingRecord.toSnapshot()
    workflowSnapshotValidator.validate(existingSnapshot, existingSnapshot.workflowName)
    if (clearOutOfBandAcceptances) {
      unitOfWork.goalRunnerControls.clearOutOfBandAcceptances(existingSnapshot.workflowId)
      unitOfWork.goalRunnerControls.clearControlState(existingSnapshot.workflowId)
    }
    val manifest =
      if (mergeConcurrentProgress) {
        mergeConcurrentGoalProgress(
          existingSnapshot.decompositionRuntime() ?: state.manifest,
          state.manifest,
        )
      } else {
        state.manifest
      }
    val updated =
      engine.updateRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        existingSnapshot,
        WorkflowUpdateInput(
          workflowStatus = existingSnapshot.workflowStatus,
          currentStepId = existingSnapshot.currentStepId,
          stepUpdates = null,
          artifactsPatch = parentProjection.artifacts(manifest, existingSnapshot.artifacts),
          sessionId = existingSnapshot.sessionId.orEmpty(),
          replaceArtifacts = true,
        ),
      )
    unitOfWork.workflowStates.saveRecord(
      WorkflowFamily.TASK_RUNTIME,
      updated.toRecord().copy(issueKey = normalizeRequiredIssueKey(manifest.issueKey)),
    )
    val refreshed = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, updated.workflowId) ?: updated
    reconcileControlStateForManifest(unitOfWork, refreshed.workflowId)
    return SavedManifestProjection(
      state =
        GoalRunnerManifestState(
          parentWorkflowId = refreshed.workflowId,
          dbPath = unitOfWork.dbPath.toString(),
          manifest = refreshed.decompositionRuntime() ?: manifest,
          controlState = unitOfWork.goalRunnerControls.controlState(refreshed.workflowId),
          repoRoot = state.repoRoot,
        ),
      projectionArtifacts = refreshed.artifacts,
    )
  }

  fun writeProjectionFile(
    state: GoalRunnerManifestState,
    projectionArtifacts: DurableWorkflowArtifacts,
  ): DecompositionManifestProjectionOutcome {
    val outcome =
      decompositionManifestWriter.writeProjectionFromWorkflowState(
        state.repoRoot ?: repositoryRoot.path,
        projectionArtifacts,
        decompositionManifestValidator,
        decompositionManifestStore,
      )
    when (outcome) {
      is DecompositionManifestProjectionOutcome.Failed ->
        database.transaction { unitOfWork ->
          persistDecompositionManifestProjectionFailure(
            engine,
            unitOfWork,
            state.parentWorkflowId,
            outcome,
          )
        }
      is DecompositionManifestProjectionOutcome.Written ->
        database.transaction { unitOfWork ->
          clearDecompositionManifestProjectionFailure(engine, unitOfWork, state.parentWorkflowId)
        }
      DecompositionManifestProjectionOutcome.Absent -> Unit
    }
    return outcome
  }
}
