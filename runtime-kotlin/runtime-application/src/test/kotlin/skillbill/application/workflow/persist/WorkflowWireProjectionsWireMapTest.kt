package skillbill.application.workflow.persist

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.contracts.workflow.session.WorkflowContinueSessionSummary
import skillbill.ports.workflow.PlainWorkflowArtifactWireEncoder
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowCompactContinueView
import skillbill.workflow.engine.model.WorkflowContinuationFieldMap
import skillbill.workflow.engine.model.WorkflowContinueView
import skillbill.workflow.engine.model.WorkflowResumeView
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowStepArtifactMap
import skillbill.workflow.engine.model.WorkflowStepState
import skillbill.workflow.engine.model.WorkflowSummaryView
import skillbill.workflow.model.WorkflowContinueStatus
import skillbill.workflow.model.WorkflowResumeMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowWireProjectionsWireMapTest {
  @Test
  fun `full workflow wire map omits mode when null and preserves key order`() {
    val wire = WorkflowWireProjections.snapshotMap(snapshotView(), PlainWorkflowArtifactWireEncoder).toPayload()

    assertFalse(wire.containsKey(WorkflowWirePayloadKeys.MODE))
    assertEquals(
      listOf(
        SharedPayloadKeys.WORKFLOW_ID,
        WorkflowWirePayloadKeys.SESSION_ID,
        WorkflowWirePayloadKeys.WORKFLOW_NAME,
        SharedPayloadKeys.CONTRACT_VERSION,
        WorkflowWirePayloadKeys.WORKFLOW_STATUS,
        WorkflowWirePayloadKeys.CURRENT_STEP_ID,
        WorkflowWirePayloadKeys.STEPS,
        WorkflowWirePayloadKeys.ARTIFACTS,
        WorkflowWirePayloadKeys.STARTED_AT,
        WorkflowWirePayloadKeys.UPDATED_AT,
        WorkflowWirePayloadKeys.FINISHED_AT,
      ),
      wire.keys.toList(),
    )
  }

  @Test
  fun `summary and resume maps preserve their fixed field order`() {
    val summary = WorkflowWireProjections.summaryMap(summaryView()).toPayload()
    assertEquals(
      listOf(
        SharedPayloadKeys.WORKFLOW_ID,
        WorkflowWirePayloadKeys.SESSION_ID,
        WorkflowWirePayloadKeys.WORKFLOW_NAME,
        SharedPayloadKeys.CONTRACT_VERSION,
        WorkflowWirePayloadKeys.WORKFLOW_STATUS,
        WorkflowWirePayloadKeys.CURRENT_STEP_ID,
        WorkflowWirePayloadKeys.STARTED_AT,
        WorkflowWirePayloadKeys.UPDATED_AT,
        WorkflowWirePayloadKeys.FINISHED_AT,
      ),
      summary.keys.toList(),
    )

    val wire = WorkflowWireProjections.resumeMap(resumeView(), PlainWorkflowArtifactWireEncoder).toPayload()
    assertEquals(
      listOf(
        SharedPayloadKeys.WORKFLOW_ID,
        WorkflowWirePayloadKeys.SESSION_ID,
        WorkflowWirePayloadKeys.WORKFLOW_NAME,
        SharedPayloadKeys.CONTRACT_VERSION,
        WorkflowWirePayloadKeys.WORKFLOW_STATUS,
        WorkflowWirePayloadKeys.CURRENT_STEP_ID,
        WorkflowWirePayloadKeys.STEPS,
        WorkflowWirePayloadKeys.ARTIFACTS,
        WorkflowWirePayloadKeys.STARTED_AT,
        WorkflowWirePayloadKeys.UPDATED_AT,
        WorkflowWirePayloadKeys.FINISHED_AT,
        WorkflowWirePayloadKeys.RESUME_MODE,
        WorkflowWirePayloadKeys.RESUME_STEP_ID,
        WorkflowWirePayloadKeys.LAST_COMPLETED_STEP_ID,
        WorkflowWirePayloadKeys.AVAILABLE_ARTIFACTS,
        WorkflowWirePayloadKeys.REQUIRED_ARTIFACTS,
        WorkflowWirePayloadKeys.MISSING_ARTIFACTS,
        WorkflowWirePayloadKeys.CAN_RESUME,
        WorkflowWirePayloadKeys.NEXT_ACTION,
      ),
      wire.keys.toList(),
    )
  }

  @Test
  fun `continue wire map preserves fixed session summary after extra-field collision`() {
    val wire =
      WorkflowWireProjections.continueMap(
        continueView(
          mapOf(
            WorkflowWirePayloadKeys.SESSION_SUMMARY to mapOf("shadow" to true),
            "marker_key" to "from_extra",
          ),
        ),
        PlainWorkflowArtifactWireEncoder,
      ).toPayload()
    val keys = wire.keys.toList()
    val extraIndex = keys.indexOf("marker_key")
    val sessionSummaryIndex = keys.indexOf(WorkflowWirePayloadKeys.SESSION_SUMMARY)

    assertEquals(
      mapOf(
        "acceptance_criteria_count" to 2,
        "rollout_relevant" to true,
        "spec_summary" to "summary",
      ),
      wire[WorkflowWirePayloadKeys.SESSION_SUMMARY],
    )
    assertTrue(sessionSummaryIndex >= 0 && extraIndex > sessionSummaryIndex)
    assertEquals("resume_existing_workflow", wire[WorkflowWirePayloadKeys.CONTINUATION_MODE])
  }

  private fun snapshotView(): WorkflowSnapshotView =
    WorkflowSnapshotView(
      workflowId = "wf-1",
      sessionId = "sess",
      workflowName = "bill-feature",
      contractVersion = "0.1",
      workflowStatus = WorkflowStatus.RUNNING,
      currentStepId = "implement",
      steps = listOf(WorkflowStepState("implement", WorkflowStepStatus.RUNNING, 1)),
      artifacts = DurableWorkflowArtifacts.EMPTY,
      startedAt = "1970-01-01T00:00:00Z",
      updatedAt = "1970-01-01T00:00:00Z",
      finishedAt = "",
    )

  private fun summaryView(): WorkflowSummaryView =
    WorkflowSummaryView(
      workflowId = "wf-1",
      sessionId = "sess",
      workflowName = "bill-feature",
      contractVersion = "0.1",
      workflowStatus = WorkflowStatus.RUNNING,
      currentStepId = "implement",
      startedAt = "1970-01-01T00:00:00Z",
      updatedAt = "1970-01-01T00:00:00Z",
      finishedAt = "",
    )

  private fun resumeView(): WorkflowResumeView =
    WorkflowResumeView(
      snapshot = snapshotView(),
      resumeMode = WorkflowResumeMode.RESUME,
      resumeStepId = "implement",
      lastCompletedStepId = "plan",
      availableArtifacts = emptyList(),
      requiredArtifacts = emptyList(),
      missingArtifacts = emptyList(),
      canResume = true,
      nextAction = "continue",
    )

  private fun continueView(extraFields: Map<String, Any?>): WorkflowContinueView =
    WorkflowContinueView(
      resume = resumeView(),
      skillName = "bill-feature",
      workflowStatusBeforeContinue = WorkflowStatus.RUNNING,
      continueStatus = WorkflowContinueStatus.REOPENED,
      continueStepId = "implement",
      continueStepLabel = "Implement",
      continueStepDirective = "do work",
      referenceSections = emptyList(),
      stepArtifactKeys = emptyList(),
      stepArtifacts = WorkflowStepArtifactMap.EMPTY,
      extraFields = WorkflowContinuationFieldMap.from(extraFields),
      sessionSummary =
        WorkflowContinueSessionSummary(
          acceptanceCriteriaCount = 2,
          rolloutRelevant = true,
          specSummary = "summary",
        ),
      continuationBrief = "brief",
      continuationEntryPrompt = "prompt",
      compact =
        WorkflowCompactContinueView(
          workflowId = "wf-1",
          skillName = "bill-feature",
          continueStatus = WorkflowContinueStatus.REOPENED,
          workflowStatusBeforeContinue = WorkflowStatus.RUNNING,
          startedAt = "1970-01-01T00:00:00Z",
          updatedAt = "1970-01-01T00:00:00Z",
          resumeStepId = "implement",
          resumeStepLabel = "Implement",
          continueStepDirective = "do work",
          referenceSections = emptyList(),
          requiredArtifactKeys = emptyList(),
          availableArtifactKeys = emptyList(),
          missingArtifactKeys = emptyList(),
          currentStepArtifacts = emptyList(),
          omittedArtifactKeys = emptyList(),
          continuationBrief = "brief",
          continuationEntryPrompt = "prompt",
          readOnlyFullStateGuidance = "read only",
        ),
    )
}
