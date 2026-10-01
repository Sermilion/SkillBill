package skillbill.application.workflow.decomposition

import skillbill.application.workflow.model.ContinueExistingWorkflowArgs
import skillbill.application.workflow.model.DecompositionRuntimeWriteArgs
import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.application.workflow.persist.toReopenInput
import skillbill.application.workflow.service.ContinuationStepResult
import skillbill.application.workflow.service.migrateLegacyGoalRunnerControls
import skillbill.application.workflow.service.withDecompositionRuntime
import skillbill.contracts.SharedPayloadKeys
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.toRecord
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.runtime.goalParentArtifactProjection
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowContinueDecisionOverrides
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepState
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

internal fun WorkflowEngine.continueExistingWorkflow(
  family: WorkflowFamily,
  initialRecord: WorkflowStateSnapshot,
  unitOfWork: UnitOfWork,
  args: ContinueExistingWorkflowArgs,
): ContinuationStepResult {
  var record = initialRecord
  val workflowId = initialRecord.workflowId
  val sessionSummary = unitOfWork.workflowStates.sessionSummary(family, record.sessionId.orEmpty())
  var decision =
    continueDecision(
      family.definition,
      record,
      sessionSummary,
      overrides =
        WorkflowContinueDecisionOverrides(
          repositoryCheckpointIdentity = args.repositoryCheckpointIdentity(),
        ),
    )
  var projectionArtifacts: DurableWorkflowArtifacts? = null
  var projectionOwnerWorkflowId: String? = null
  if (decision.shouldReopen) {
    val originalContinueStatus = decision.view.continueStatus
    val originalWorkflowStatus = decision.view.workflowStatusBeforeContinue
    val reopenInput = decision.toReopenInput(record.sessionId)
    val effectiveInput =
      if (canRefreshDecompositionRuntime(family, args)) {
        family.withDecompositionRuntime(
          DecompositionRuntimeWriteArgs(
            existing = record,
            input = reopenInput,
            planningResult = null,
            workflowId = workflowId,
            validator = requireNotNull(args.validator),
            fileStore = requireNotNull(args.fileStore),
            repoRoot = requireNotNull(args.repoRoot),
            manifestWriter = requireNotNull(args.manifestWriter),
          ),
        ).input
      } else {
        reopenInput
      }
    val reopened = updateRecord(family.definition, record, effectiveInput)
    unitOfWork.workflowStates.save(family, reopened)
    record = unitOfWork.workflowStates.get(family, workflowId) ?: reopened
    val reopenValidator = args.validator
    if (family == WorkflowFamily.TASK_RUNTIME && reopenValidator != null) {
      projectionOwnerWorkflowId = resolveDecompositionProjectionOwner(record, unitOfWork)
      if (projectionOwnerWorkflowId != null) {
        projectionArtifacts = record.artifacts
      }
    }
    decision =
      continueDecision(
        family.definition,
        record,
        sessionSummary,
        overrides =
          WorkflowContinueDecisionOverrides(
            continueStatus = originalContinueStatus,
            workflowStatusBeforeContinue = originalWorkflowStatus,
            repositoryCheckpointIdentity = args.repositoryCheckpointIdentity(),
          ),
      )
  }
  return ContinuationStepResult(
    WorkflowContinueResult.Standard(
      dbPath = unitOfWork.dbPath.toString(),
      view = decision.view,
    ),
    projectionArtifacts = projectionArtifacts,
    projectionOwnerWorkflowId = projectionOwnerWorkflowId,
  )
}

private fun canRefreshDecompositionRuntime(
  family: WorkflowFamily,
  args: ContinueExistingWorkflowArgs,
): Boolean = family == WorkflowFamily.TASK_RUNTIME && args.hasWriteTargets()

private fun ContinueExistingWorkflowArgs.hasWriteTargets(): Boolean =
  validator != null && repoRoot != null && manifestWriter != null && fileStore != null

fun WorkflowEngine.alignSubtaskResumeStep(
  record: WorkflowStateSnapshot,
  resumeStepId: String,
  unitOfWork: UnitOfWork,
): WorkflowStateSnapshot {
  val alignment = resumeAlignment(record, resumeStepId)
  if (
    alignment.targetStepId.isBlank() ||
    (record.currentStepId == alignment.targetStepId && alignment.staleBlockedStep == null)
  ) {
    return record
  }
  val updated =
    updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = record.workflowStatus,
        currentStepId = alignment.targetStepId,
        stepUpdates =
          alignment.staleBlockedStep?.let { step ->
            WorkflowStepUpdates.from(
              listOf(
                mapOf(
                  SharedPayloadKeys.STEP_ID to step.stepId,
                  SharedPayloadKeys.STATUS to WorkflowStepStatus.COMPLETED.wireValue,
                  "attempt_count" to step.attemptCount,
                ),
              ),
            )
          },
        artifactsPatch = null,
        sessionId = record.sessionId.orEmpty(),
      ),
    )
  unitOfWork.workflowStates.save(WorkflowFamily.TASK_RUNTIME, updated)
  return unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, record.workflowId) ?: updated
}

private fun WorkflowEngine.resumeAlignment(
  record: WorkflowStateSnapshot,
  requestedStepId: String,
): ResumeAlignment {
  val steps = snapshotView(WorkflowFamily.TASK_RUNTIME.definition, record).steps
  val requestedStep = steps.firstOrNull { step -> step.stepId == requestedStepId }
  val targetStepId =
    requestedStepId.takeIf { stepId ->
      stepId.isNotBlank() && steps.firstOrNull { step ->
        step.stepId == stepId && step.status.workflowStepStatus() == WorkflowStepStatus.RUNNING
      } != null
    }
      ?: steps.firstOrNull { step -> step.status.workflowStepStatus() == WorkflowStepStatus.RUNNING }?.stepId
      ?: requestedStepId
  val staleBlockedStep =
    requestedStep?.takeIf {
      it.stepId != targetStepId && it.status.workflowStepStatus() == WorkflowStepStatus.BLOCKED
    }
  return ResumeAlignment(targetStepId = targetStepId, staleBlockedStep = staleBlockedStep)
}

private data class ResumeAlignment(
  val targetStepId: String,
  val staleBlockedStep: WorkflowStepState?,
)

fun WorkflowEngine.persistParentDecompositionRuntime(
  parentRecord: WorkflowStateSnapshot,
  manifest: DecompositionManifest,
  unitOfWork: UnitOfWork,
  validator: DecompositionManifestValidator,
) {
  migrateLegacyGoalRunnerControls(unitOfWork, parentRecord)
  val updatedParent =
    updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      parentRecord,
      WorkflowUpdateInput(
        workflowStatus = parentRecord.workflowStatus,
        currentStepId = parentRecord.currentStepId,
        stepUpdates = null,
        artifactsPatch =
          goalParentArtifactProjection(
            parentRecord.artifacts,
            FeatureTaskRuntimeWorkflowArtifactMap.from(
              validator.encodeManifestWireMap(
                manifest,
                DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label(),
              ),
            ),
          ),
        sessionId = parentRecord.sessionId.orEmpty(),
        replaceArtifacts = true,
      ),
    )
  unitOfWork.workflowStates.saveRecord(
    WorkflowFamily.TASK_RUNTIME,
    updatedParent.toRecord().copy(issueKey = manifest.issueKey),
  )
}
