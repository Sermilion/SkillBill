package skillbill.application.workflow.persist

import skillbill.contracts.JsonCodec
import skillbill.contracts.JsonPayloadContract
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.ports.workflow.WorkflowArtifactWireEncoder
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowCompactContinueView
import skillbill.workflow.engine.model.WorkflowContinuationArtifactSummary
import skillbill.workflow.engine.model.WorkflowContinueView
import skillbill.workflow.engine.model.WorkflowInputProjection
import skillbill.workflow.engine.model.WorkflowResumeView
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowStepState
import skillbill.workflow.engine.model.WorkflowSummaryView
import skillbill.workflow.engine.model.WorkflowUpdateAcknowledgementView

object WorkflowWireProjections {
  fun snapshotMap(
    view: WorkflowSnapshotView,
    encoder: WorkflowArtifactWireEncoder,
  ): JsonPayloadContract =
    payload(
      linkedMapOf<String, Any?>().apply {
        putSnapshotFields(view, encoder)
        removeModeWhenNull(view.mode)
      },
    )

  fun summaryMap(view: WorkflowSummaryView): JsonPayloadContract =
    payload(
      linkedMapOf<String, Any?>().apply {
        put(SharedPayloadKeys.WORKFLOW_ID, view.workflowId)
        put(WorkflowWirePayloadKeys.SESSION_ID, view.sessionId)
        put(WorkflowWirePayloadKeys.WORKFLOW_NAME, view.workflowName)
        put(WorkflowWirePayloadKeys.MODE, view.mode)
        put(SharedPayloadKeys.CONTRACT_VERSION, view.contractVersion)
        put(WorkflowWirePayloadKeys.WORKFLOW_STATUS, view.workflowStatus.wireValue)
        put(WorkflowWirePayloadKeys.CURRENT_STEP_ID, view.currentStepId)
        put(WorkflowWirePayloadKeys.STARTED_AT, view.startedAt)
        put(WorkflowWirePayloadKeys.UPDATED_AT, view.updatedAt)
        put(WorkflowWirePayloadKeys.FINISHED_AT, view.finishedAt)
        removeModeWhenNull(view.mode)
      },
    )

  fun resumeMap(
    view: WorkflowResumeView,
    encoder: WorkflowArtifactWireEncoder,
  ): JsonPayloadContract =
    payload(
      linkedMapOf<String, Any?>().apply {
        putSnapshotFields(view.snapshot, encoder)
        removeModeWhenNull(view.snapshot.mode)
        putResumeFields(view)
      },
    )

  fun continueMap(
    view: WorkflowContinueView,
    encoder: WorkflowArtifactWireEncoder,
  ): JsonPayloadContract =
    payload(
      linkedMapOf<String, Any?>().apply {
        putSnapshotFields(view.resume.snapshot, encoder)
        removeModeWhenNull(view.resume.snapshot.mode)
        putResumeFields(view.resume)
        put(WorkflowWirePayloadKeys.SKILL_NAME, view.skillName)
        put(WorkflowWirePayloadKeys.CONTINUATION_MODE, "resume_existing_workflow")
        put(WorkflowWirePayloadKeys.WORKFLOW_STATUS_BEFORE_CONTINUE, view.workflowStatusBeforeContinue.wireValue)
        put(WorkflowWirePayloadKeys.CONTINUE_STATUS, view.continueStatus.wireValue)
        put(WorkflowWirePayloadKeys.CONTINUE_STEP_ID, view.continueStepId)
        put(WorkflowWirePayloadKeys.CONTINUE_STEP_LABEL, view.continueStepLabel)
        put(WorkflowWirePayloadKeys.CONTINUE_STEP_DIRECTIVE, view.continueStepDirective)
        put(WorkflowWirePayloadKeys.REFERENCE_SECTIONS, view.referenceSections)
        put(WorkflowWirePayloadKeys.STEP_ARTIFACT_KEYS, view.stepArtifactKeys)
        put(WorkflowWirePayloadKeys.STEP_ARTIFACTS, view.stepArtifacts.toMap())
        view.extraFields.forEach { (key, value) -> put(key, value) }
        put(WorkflowWirePayloadKeys.SESSION_SUMMARY, view.sessionSummary.toPayload())
        put(WorkflowWirePayloadKeys.CONTINUATION_BRIEF, view.continuationBrief)
        put(WorkflowWirePayloadKeys.CONTINUATION_ENTRY_PROMPT, view.continuationEntryPrompt)
      },
    )

  fun compactContinueMap(view: WorkflowCompactContinueView): JsonPayloadContract =
    payload(
      linkedMapOf(
        SharedPayloadKeys.WORKFLOW_ID to view.workflowId,
        WorkflowWirePayloadKeys.SKILL_NAME to view.skillName,
        WorkflowWirePayloadKeys.WORKFLOW_STATUS_BEFORE_CONTINUE to view.workflowStatusBeforeContinue.wireValue,
        WorkflowWirePayloadKeys.STARTED_AT to view.startedAt,
        WorkflowWirePayloadKeys.UPDATED_AT to view.updatedAt,
        WorkflowWirePayloadKeys.CONTINUE_STATUS to view.continueStatus.wireValue,
        WorkflowWirePayloadKeys.RESUME_STEP_ID to view.resumeStepId,
        WorkflowWirePayloadKeys.RESUME_STEP_LABEL to view.resumeStepLabel,
        WorkflowWirePayloadKeys.CONTINUE_STEP_ID to view.resumeStepId,
        WorkflowWirePayloadKeys.CONTINUE_STEP_LABEL to view.resumeStepLabel,
        WorkflowWirePayloadKeys.CONTINUE_STEP_DIRECTIVE to view.continueStepDirective,
        WorkflowWirePayloadKeys.REFERENCE_SECTIONS to view.referenceSections,
        WorkflowWirePayloadKeys.REQUIRED_ARTIFACT_KEYS to view.requiredArtifactKeys,
        WorkflowWirePayloadKeys.AVAILABLE_ARTIFACT_KEYS to view.availableArtifactKeys,
        WorkflowWirePayloadKeys.MISSING_ARTIFACT_KEYS to view.missingArtifactKeys,
        WorkflowWirePayloadKeys.REQUIRED_ARTIFACTS to view.requiredArtifactKeys,
        WorkflowWirePayloadKeys.AVAILABLE_ARTIFACTS to view.availableArtifactKeys,
        WorkflowWirePayloadKeys.MISSING_ARTIFACTS to view.missingArtifactKeys,
        WorkflowWirePayloadKeys.CURRENT_STEP_ARTIFACTS to view.currentStepArtifacts.map(::artifactSummaryMap),
        WorkflowWirePayloadKeys.OMITTED_ARTIFACT_KEYS to view.omittedArtifactKeys,
        WorkflowWirePayloadKeys.CONTINUATION_BRIEF to view.continuationBrief,
        WorkflowWirePayloadKeys.CONTINUATION_ENTRY_PROMPT to view.continuationEntryPrompt,
        WorkflowWirePayloadKeys.READ_ONLY_FULL_STATE_GUIDANCE to view.readOnlyFullStateGuidance,
      ),
    )

  fun updateAcknowledgementMap(view: WorkflowUpdateAcknowledgementView): JsonPayloadContract =
    payload(
      linkedMapOf(
        SharedPayloadKeys.STATUS to view.status,
        SharedPayloadKeys.WORKFLOW_ID to view.workflowId,
        WorkflowWirePayloadKeys.WORKFLOW_NAME to view.workflowName,
        WorkflowWirePayloadKeys.WORKFLOW_STATUS to view.workflowStatus.wireValue,
        WorkflowWirePayloadKeys.CURRENT_STEP_ID to view.currentStepId,
        WorkflowWirePayloadKeys.UPDATED_STEP_IDS to view.updatedStepIds,
        WorkflowWirePayloadKeys.UPDATED_ARTIFACT_KEYS to view.updatedArtifactKeys,
        WorkflowWirePayloadKeys.READ_ONLY_FULL_STATE_GUIDANCE to view.readOnlyFullStateGuidance,
      ),
    )

  fun inputProjectionMap(
    projection: WorkflowInputProjection,
    encoder: WorkflowArtifactWireEncoder,
  ): JsonPayloadContract =
    payload(
      linkedMapOf(
        SharedPayloadKeys.STEP_ID to projection.stepId,
        WorkflowWirePayloadKeys.PRODUCER_ITERATION to projection.producerIteration,
        ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT to projection.repositoryCheckpoint,
        WorkflowWirePayloadKeys.ARTIFACTS to
          JsonCodec.parseValue(
            encoder.encode(DurableWorkflowArtifacts.fromMap(projection.artifacts)),
          ),
        WorkflowWirePayloadKeys.UTF8_BYTES to projection.utf8Bytes,
      ),
    )

  private fun payload(map: Map<String, Any?>): JsonPayloadContract = WorkflowWirePayload(map)

  private fun MutableMap<String, Any?>.putSnapshotFields(
    view: WorkflowSnapshotView,
    encoder: WorkflowArtifactWireEncoder,
  ) {
    put(SharedPayloadKeys.WORKFLOW_ID, view.workflowId)
    put(WorkflowWirePayloadKeys.SESSION_ID, view.sessionId)
    put(WorkflowWirePayloadKeys.WORKFLOW_NAME, view.workflowName)
    put(WorkflowWirePayloadKeys.MODE, view.mode)
    put(SharedPayloadKeys.CONTRACT_VERSION, view.contractVersion)
    put(WorkflowWirePayloadKeys.WORKFLOW_STATUS, view.workflowStatus.wireValue)
    put(WorkflowWirePayloadKeys.CURRENT_STEP_ID, view.currentStepId)
    put(WorkflowWirePayloadKeys.STEPS, view.steps.map(::workflowStepWireMap))
    put(WorkflowWirePayloadKeys.ARTIFACTS, JsonCodec.parseValue(encoder.encode(view.artifacts)))
    put(WorkflowWirePayloadKeys.STARTED_AT, view.startedAt)
    put(WorkflowWirePayloadKeys.UPDATED_AT, view.updatedAt)
    put(WorkflowWirePayloadKeys.FINISHED_AT, view.finishedAt)
  }

  private fun MutableMap<String, Any?>.putResumeFields(view: WorkflowResumeView) {
    put(WorkflowWirePayloadKeys.RESUME_MODE, view.resumeMode.wireValue)
    put(WorkflowWirePayloadKeys.RESUME_STEP_ID, view.resumeStepId)
    put(WorkflowWirePayloadKeys.LAST_COMPLETED_STEP_ID, view.lastCompletedStepId)
    put(WorkflowWirePayloadKeys.AVAILABLE_ARTIFACTS, view.availableArtifacts)
    put(WorkflowWirePayloadKeys.REQUIRED_ARTIFACTS, view.requiredArtifacts)
    put(WorkflowWirePayloadKeys.MISSING_ARTIFACTS, view.missingArtifacts)
    put(WorkflowWirePayloadKeys.CAN_RESUME, view.canResume)
    put(WorkflowWirePayloadKeys.NEXT_ACTION, view.nextAction)
  }

  private fun MutableMap<String, Any?>.removeModeWhenNull(mode: String?) {
    if (mode == null) {
      remove(WorkflowWirePayloadKeys.MODE)
    }
  }
}

private fun artifactSummaryMap(summary: WorkflowContinuationArtifactSummary): Map<String, Any?> =
  linkedMapOf(
    WorkflowWirePayloadKeys.KEY to summary.key,
    WorkflowWirePayloadKeys.PRESENT to summary.present,
    WorkflowWirePayloadKeys.INLINE to summary.inline,
    WorkflowWirePayloadKeys.SIZE_BYTES to summary.sizeBytes,
    SharedPayloadKeys.VALUE to summary.value.raw,
    WorkflowWirePayloadKeys.PREVIEW to summary.preview,
    WorkflowWirePayloadKeys.TRUNCATED to summary.truncated,
    WorkflowWirePayloadKeys.OMITTED to summary.omitted,
    WorkflowWirePayloadKeys.OMISSION_REASON to summary.omissionReason,
  )

private class WorkflowWirePayload(
  private val payload: Map<String, Any?>,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = payload
}

private fun workflowStepWireMap(step: WorkflowStepState): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STEP_ID to step.stepId,
    SharedPayloadKeys.STATUS to step.status.wireValue,
    WorkflowWirePayloadKeys.ATTEMPT_COUNT to step.attemptCount,
  )
