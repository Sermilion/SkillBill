package skillbill.application.workflow.service

import skillbill.application.decomposition.model.DecompositionManifestRuntimeUpdate
import skillbill.application.decomposition.model.DecompositionManifestWorkflowProjectionInput
import skillbill.application.workflow.decomposition.findDecomposedParentWorkflowForRuntime
import skillbill.application.workflow.decomposition.persistParentDecompositionRuntime
import skillbill.application.workflow.model.DecompositionRuntimeWriteArgs
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput

internal fun WorkflowFamily.withDecompositionRuntime(args: DecompositionRuntimeWriteArgs): DecompositionRuntimeInput =
  if (this != WorkflowFamily.TASK_RUNTIME) {
    DecompositionRuntimeInput(input = args.input, updated = false)
  } else {
    args.manifestWriter.manifestFromWorkflowUpdate(
      DecompositionManifestWorkflowProjectionInput(
        repoRoot = args.repoRoot,
        existingArtifacts = args.existing.artifacts,
        validator = args.validator,
        planningResult = args.planningResult,
        artifactsPatch = args.input.artifactsPatch,
        runtimeUpdate =
          DecompositionManifestRuntimeUpdate(
            workflowId = args.workflowId,
            workflowStatus = args.input.workflowStatus.wireValue,
            currentStepId = args.input.currentStepId,
            stepUpdates = args.input.stepUpdates,
          ),
        fileStore = args.fileStore,
      ),
    )?.let { manifest ->
      DecompositionRuntimeInput(
        input =
          args.input.copy(
            artifactsPatch =
              WorkflowArtifactPatch.from(
                LinkedHashMap(args.input.artifactsPatch.orEmpty()).apply {
                  DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.putInto(
                    this,
                    args.validator.encodeManifestWireMap(
                      manifest,
                      DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label(),
                    ),
                  )
                },
              ),
          ),
        updated = true,
      )
    } ?: DecompositionRuntimeInput(input = args.input, updated = false)
  }

internal data class DecompositionRuntimeInput(
  val input: WorkflowUpdateInput,
  val updated: Boolean,
)

internal fun WorkflowEngine.syncDecompositionParentRuntime(
  family: WorkflowFamily,
  updated: WorkflowStateSnapshot,
  workflowId: String,
  unitOfWork: UnitOfWork,
  validator: DecompositionManifestValidator,
) {
  val manifest = updated.decompositionRuntime()
  if (family == WorkflowFamily.TASK_RUNTIME && manifest != null) {
    val parent = unitOfWork.workflowStates.findDecomposedParentWorkflowForRuntime(manifest)
    parent?.toSnapshot()
      ?.takeUnless { it.workflowId == workflowId }
      ?.let { p -> persistParentDecompositionRuntime(p, manifest, unitOfWork, validator) }
  }
}
