package skillbill.engine.goalrunner.archive

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.goal.GoalArchiveRecordKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.ports.workflow.WorkflowArtifactWireEncoder
import skillbill.ports.workflow.model.WorkflowStateRecord

internal fun WorkflowStateRecord.toGoalArchiveFieldMap(encoder: WorkflowArtifactWireEncoder): Map<String, Any?> =
  linkedMapOf<String, Any?>(
    SharedPayloadKeys.WORKFLOW_ID to workflowId,
    WorkflowWirePayloadKeys.SESSION_ID to sessionId,
    WorkflowWirePayloadKeys.WORKFLOW_NAME to workflowName,
    SharedPayloadKeys.CONTRACT_VERSION to contractVersion,
    WorkflowWirePayloadKeys.WORKFLOW_STATUS to workflowStatus,
    WorkflowWirePayloadKeys.CURRENT_STEP_ID to currentStepId,
    GoalArchiveRecordKeys.STEPS_JSON to stepsJson,
    GoalArchiveRecordKeys.ARTIFACTS_JSON to artifactsJson,
    WorkflowWirePayloadKeys.STARTED_AT to startedAt,
    WorkflowWirePayloadKeys.UPDATED_AT to updatedAt,
    WorkflowWirePayloadKeys.FINISHED_AT to finishedAt,
    GoalArchiveRecordKeys.IMPLEMENTATION_SKILL to implementationSkill,
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    GoalArchiveRecordKeys.STATE_ENTERED_AT to stateEnteredAt,
    GoalArchiveRecordKeys.STATE_ENTERED_AT_ESTIMATED to stateEnteredAtEstimated,
    GoalArchiveRecordKeys.TYPED_ARTIFACTS to JsonCodec.parseValue(encoder.encode(typedArtifacts)),
  ).apply {
    mode?.let { put(WorkflowWirePayloadKeys.MODE, it.wireValue) }
  }
