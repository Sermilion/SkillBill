package skillbill.application.workflow.decomposition

import skillbill.application.workflow.model.GoalContinuationOutcome
import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.workflow.decomposition.model.DecompositionContinuationSelection
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot

internal data class ContinuationStepResult(
  val result: WorkflowContinueResult,
  val projectionArtifacts: DurableWorkflowArtifacts? = null,
  val projectionOwnerWorkflowId: String? = null,
) {
  fun withProjection(
    manifest: DecompositionManifest,
    validator: DecompositionManifestValidator,
    ownerWorkflowId: String,
  ): ContinuationStepResult =
    withPendingProjection(
      ownerWorkflowId = ownerWorkflowId,
      artifacts = decompositionRuntimeArtifacts(manifest, validator),
    )

  fun withProjectionArtifactsIfMissing(artifacts: DurableWorkflowArtifacts?): ContinuationStepResult =
    if (projectionArtifacts == null && artifacts != null) {
      copy(projectionArtifacts = artifacts)
    } else {
      this
    }

  fun withDecompositionFields(
    issueKey: String,
    subtaskId: Int,
    specPath: String,
    outcome: GoalContinuationOutcome,
  ): ContinuationStepResult {
    val decorated: WorkflowContinueResult =
      when (val current = result) {
        is WorkflowContinueResult.Standard ->
          WorkflowContinueResult.DecompositionStandard(
            dbPath = current.dbPath,
            view = current.view,
            decompositionSubtaskId = subtaskId,
            decompositionSubtaskSpecPath = specPath,
            issueKey = issueKey,
            outcome = outcome,
          )
        is WorkflowContinueResult.DecompositionStandard ->
          current.copy(
            decompositionSubtaskId = subtaskId,
            decompositionSubtaskSpecPath = specPath,
            issueKey = issueKey,
            outcome = outcome,
          )
        is WorkflowContinueResult.UnknownWorkflow,
        is WorkflowContinueResult.DecompositionMissingSubtaskWorkflow,
        is WorkflowContinueResult.DecompositionBlockedSubtask,
        is WorkflowContinueResult.DecompositionBlockedBranchStart,
        is WorkflowContinueResult.DecompositionDone,
        is WorkflowContinueResult.DecompositionSubtaskOutcome,
        is WorkflowContinueResult.DecompositionBlockedGit,
        is WorkflowContinueResult.Error,
        ->
          error(
            "withDecompositionFields can only decorate Standard or " +
              "DecompositionStandard continuations; got ${current::class.simpleName}",
          )
      }
    return copy(result = decorated)
  }
}

internal fun missingSubtaskWorkflowResult(
  selection: DecompositionContinuationSelection.Resume,
  unitOfWork: UnitOfWork,
): ContinuationStepResult =
  ContinuationStepResult(
    WorkflowContinueResult.DecompositionMissingSubtaskWorkflow(
      dbPath = unitOfWork.dbPath.toString(),
      subtaskId = selection.subtask.id,
      blockedReason = "Subtask ${selection.subtask.id} is in progress but has no workflow_id.",
    ),
  )

internal fun blockedSubtaskResult(
  parentRecord: WorkflowStateSnapshot,
  manifest: DecompositionManifest,
  selection: DecompositionContinuationSelection.Blocked,
  dbPath: String,
): WorkflowContinueResult =
  WorkflowContinueResult.DecompositionBlockedSubtask(
    dbPath = dbPath,
    workflowId = parentRecord.workflowId,
    issueKey = manifest.issueKey,
    subtaskId = selection.subtask.id,
    subtaskSpecPath = selection.subtask.specPath,
    blockedReason = selection.reason,
  )

internal fun doneDecompositionResult(
  parentRecord: WorkflowStateSnapshot,
  manifest: DecompositionManifest,
  dbPath: String,
): WorkflowContinueResult =
  WorkflowContinueResult.DecompositionDone(
    dbPath = dbPath,
    workflowId = parentRecord.workflowId,
    issueKey = manifest.issueKey,
    decompositionStatus = manifest.status,
  )

internal fun blockedGitResult(
  parentWorkflowId: String,
  issueKey: String,
  dbPath: String,
  reason: String,
): WorkflowContinueResult =
  WorkflowContinueResult.DecompositionBlockedGit(
    dbPath = dbPath,
    workflowId = parentWorkflowId,
    issueKey = issueKey,
    blockedReason = reason.ifBlank { "Subtask advancement failed." },
  )

internal fun decompositionRuntimeArtifacts(
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
): DurableWorkflowArtifacts =
  DurableWorkflowArtifacts.fromMap(
    mapOf(
      DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.entry(
        validator.encodeManifestWireMap(
          manifest,
          DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label(),
        ),
      ),
    ),
  )
