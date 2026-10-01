package skillbill.engine.goalrunner.manifest

import skillbill.contracts.issuekey.normalizeRequiredIssueKey
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.toRecord
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.decomposition.runtime.goalParentArtifactProjection
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

internal class GoalParentProjectionWriter(
  private val engine: WorkflowEngine,
  private val validator: DecompositionManifestValidator,
) {
  fun artifacts(
    manifest: DecompositionManifest,
    existing: DurableWorkflowArtifacts = DurableWorkflowArtifacts.EMPTY,
  ): WorkflowArtifactPatch =
    goalParentArtifactProjection(
      existing,
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        validator.encodeManifestWireMap(
          manifest,
          DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label(),
        ),
      ),
    )

  fun rewrite(
    unitOfWork: GoalRunnerPersistenceSession,
    existing: WorkflowStateSnapshot,
  ) {
    val manifest =
      existing.artifacts.decompositionRuntime()
        ?: error("Goal parent workflow '${existing.workflowId}' has no decomposition manifest.")
    val updated =
      engine.updateRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        existing,
        WorkflowUpdateInput(
          workflowStatus = existing.workflowStatus,
          currentStepId = existing.currentStepId,
          stepUpdates = null,
          artifactsPatch = artifacts(manifest, existing.artifacts),
          sessionId = existing.sessionId,
          replaceArtifacts = true,
        ),
      )
    unitOfWork.workflowStates.saveRecord(
      WorkflowFamily.TASK_RUNTIME,
      updated.toRecord().copy(issueKey = normalizeRequiredIssueKey(manifest.issueKey)),
    )
  }
}
