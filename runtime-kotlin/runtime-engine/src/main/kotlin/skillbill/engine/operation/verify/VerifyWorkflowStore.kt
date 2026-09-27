package skillbill.engine.operation.verify

import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowGetResult
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.application.workflow.service.WorkflowService
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.model.WorkflowStatus

internal class VerifyWorkflowStore(
  private val workflows: WorkflowService,
) {
  fun write(
    workflowId: String,
    status: WorkflowStatus,
    currentStepId: String,
    steps: List<Map<String, Any?>>,
    artifacts: Map<String, Any?>? = null,
  ): VerifyWrite =
    update(
      WorkflowUpdateRequest(
        workflowId = workflowId,
        workflowStatus = status.wireValue,
        currentStepId = currentStepId,
        stepUpdates = WorkflowStepUpdates.from(steps),
        artifactsPatch = WorkflowArtifactPatch.from(artifacts),
      ),
    )

  fun begin(
    workflowId: String,
    currentStepId: String,
    steps: List<Map<String, Any?>>,
    sessionId: String,
  ): VerifyWrite =
    update(
      WorkflowUpdateRequest(
        workflowId = workflowId,
        workflowStatus = WorkflowStatus.RUNNING.wireValue,
        currentStepId = currentStepId,
        stepUpdates = WorkflowStepUpdates.from(steps),
        artifactsPatch = WorkflowArtifactPatch.from(null),
        sessionId = sessionId,
      ),
    )

  private fun update(request: WorkflowUpdateRequest): VerifyWrite =
    when (val result = workflows.update(WorkflowFamilyKind.VERIFY, request)) {
      is WorkflowUpdateResult.Ok -> VerifyWrite.Ok(priorValuesOf(result.launchProjection?.artifacts))
      is WorkflowUpdateResult.Error -> VerifyWrite.Rejected(result.error)
    }

  fun artifacts(workflowId: String): Map<String, Any?> =
    try {
      (workflows.get(WorkflowFamilyKind.VERIFY, workflowId) as? WorkflowGetResult.Ok)?.snapshot?.artifacts.orEmpty()
    } catch (_: InvalidWorkflowStateSchemaError) {
      emptyMap()
    }
}

internal sealed interface VerifyWrite {
  data class Ok(val priorValues: Map<String, String>) : VerifyWrite

  data class Rejected(val error: String) : VerifyWrite
}
