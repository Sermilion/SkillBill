package skillbill.application.workflow.decomposition

import skillbill.workflow.engine.model.WorkflowArtifactPatch
import kotlin.test.Test
import kotlin.test.assertEquals

class PlanWorkflowLinkageTest {
  @Test
  fun `an imported parent with no plan workflow keeps the plain completed preplan and plan stubs`() {
    val updates = importedPlanStepUpdates(null).asEntries()

    assertEquals(
      listOf(
        mapOf("step_id" to "preplan", "status" to "completed", "attempt_count" to 1),
        mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1),
      ),
      updates,
    )
    val patch = requireNotNull(WorkflowArtifactPatch.from(mapOf("goal" to "kept")))
    assertEquals(patch, patch.withImportedPlan(null))
  }

  @Test
  fun `an imported parent names its completed plan workflow on both stub steps and in its artifacts`() {
    val updates = importedPlanStepUpdates("wftr-plan-1").asEntries()

    assertEquals(listOf("preplan", "plan"), updates.map { it["step_id"] })
    assertEquals(listOf("wftr-plan-1", "wftr-plan-1"), updates.map { it["plan_workflow_id"] })
    val patch = requireNotNull(WorkflowArtifactPatch.from(mapOf("goal" to "kept")))
    val linked = patch.withImportedPlan("wftr-plan-1")
    assertEquals("kept", linked["goal"])
    assertEquals(mapOf("workflow_id" to "wftr-plan-1"), linked["plan_workflow"])
  }
}
