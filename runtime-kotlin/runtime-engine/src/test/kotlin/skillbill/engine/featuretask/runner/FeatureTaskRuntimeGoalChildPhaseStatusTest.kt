package skillbill.engine.featuretask.runner

import skillbill.engine.featuretask.lifecycle.continuation.GoalContinuationStateRecordRequest
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeStatusRequest
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeGoalChildPhaseStatusTest {
  @Test
  fun `goal child subtask presents no qa steps while a standalone feature run keeps them`() {
    val standalone = statusHarness()
    standalone.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    standalone.recordCompleted("audit", 1)
    val standalonePhases = phaseIds(standalone)
    assertTrue("qa" in standalonePhases && "qa_fix" in standalonePhases)

    val child = statusHarness()
    child.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    recordGoalContinuation(child)
    child.recordCompleted("audit", 1)
    val childPhases = phaseIds(child)
    assertFalse("qa" in childPhases || "qa_fix" in childPhases, "Goal child phases: $childPhases")
  }

  private fun phaseIds(harness: StatusHarness): List<String> =
    assertNotNull(harness.service.status(FeatureTaskRuntimeStatusRequest(WORKFLOW_ID))).phases.map { it.phaseId }

  private fun recordGoalContinuation(harness: StatusHarness) {
    check(
      harness.goalContinuationRecorder.recordGoalContinuationState(
        GoalContinuationStateRecordRequest(
          workflowId = WORKFLOW_ID,
          continuation =
            FeatureTaskRuntimeGoalContinuationArtifact(
              issueKey = "SKILL-1",
              subtaskId = 1,
              suppressPr = true,
              goalBranch = "feat/SKILL-1-goal",
              parentWorkflowId = "wfgr-parent",
              codeReviewMode = CodeReviewExecutionMode.DEFAULT,
            ),
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
      ),
    )
  }

  private companion object {
    const val WORKFLOW_ID = "wftr-20260603-status-0001"
    const val SESSION_ID = "ftr-status-001"
  }
}
