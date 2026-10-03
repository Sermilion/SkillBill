package skillbill.engine.goalrunner.planning.state

import skillbill.engine.featuretask.runner.SlotBaselineGoalPlanningCapture
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GoalPlanningRunLoopPersistenceTest {
  @Test
  fun `a two-subtask goal planning prepare over the run loop writes no feature-task workflow row`() {
    assertEquals(emptyList(), SlotBaselineGoalPlanningCapture.preparedRows("feature_task_workflows"))
  }

  @Test
  fun `a goal child hydrates the shared preplan and its own plan that the run loop checkpointed`() {
    val child = SlotBaselineGoalPlanningCapture.hydratedChild(1)
    val artifacts = child.hydration.artifacts
    val records = assertIs<Map<*, *>>(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.value(artifacts))

    assertEquals("implement", child.hydration.currentStepId)
    assertEquals(child.preplanPayload, assertIs<Map<*, *>>(records["preplan"])["output_artifact"])
    assertEquals(child.planPayload, assertIs<Map<*, *>>(records["plan"])["output_artifact"])
  }
}
