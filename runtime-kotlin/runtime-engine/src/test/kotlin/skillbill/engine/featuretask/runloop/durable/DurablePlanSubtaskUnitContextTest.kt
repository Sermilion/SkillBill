package skillbill.engine.featuretask.runloop.durable

import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundle
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundleOrigin
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundleSubtask
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DurablePlanSubtaskUnitContextTest {
  @Test
  fun `a plan subtask unit launch names only its own sub-spec`() {
    val context = planSubtaskLaunchContext(bundle, 2)

    assertContains(context, "Current governed sub-spec: .feature-specs/SKILL-9/spec_subtask_2.md")
    assertFalse(context.contains("spec_subtask_1.md"))
  }

  @Test
  fun `a unit absent from the recorded bundle refuses to launch`() {
    assertFailsWith<IllegalStateException> { planSubtaskLaunchContext(bundle, 3) }
    assertFailsWith<IllegalStateException> { planSubtaskLaunchContext(null, 1) }
  }

  @Test
  fun `a plan subtask unit is seeded with the preplan and plan records and nothing else`() {
    val records =
      listOf("intake", "preplan", "plan", "plan_subtasks", "implement").associateWith(::record)

    assertEquals(setOf("preplan", "plan"), planSubtaskUpstreamRecords(records).keys)
  }

  private fun record(phaseId: String) =
    FeatureTaskRuntimePhaseRecord(
      phaseId = phaseId,
      status = WorkflowStepStatus.COMPLETED,
      attemptCount = 1,
      startedAt = Instant.EPOCH,
      resolvedAgentId = "claude",
    )

  private companion object {
    val bundle =
      FeatureTaskRuntimePlanBundle(
        origin = FeatureTaskRuntimePlanBundleOrigin.AUTHORED,
        parentSpecSha256 = "p",
        manifestSha256 = "m",
        subtasks =
          listOf(
            FeatureTaskRuntimePlanBundleSubtask(1, ".feature-specs/SKILL-9/spec_subtask_1.md", "s1"),
            FeatureTaskRuntimePlanBundleSubtask(2, ".feature-specs/SKILL-9/spec_subtask_2.md", "s2"),
          ),
      )
  }
}
