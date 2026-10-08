package skillbill.workflow.taskruntime.model.core

import skillbill.error.featuretask.UnknownPhaseStepError
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PhaseSlotTest {
  @Test
  fun `every runtime phase step belongs to exactly one slot`() {
    val owners = FeatureTaskRuntimePhaseIds.all.associateWith { step -> PhaseSlot.entries.filter { step in it.steps } }

    owners.forEach { (step, slots) -> assertEquals(1, slots.size, "step '$step' owners: $slots") }
    assertEquals(FeatureTaskRuntimePhaseIds.all.sorted(), PhaseSlot.entries.flatMap { it.steps }.sorted())
    assertEquals(emptyList<PhaseSlot>(), PhaseSlot.entries.filter { it.steps.isEmpty() })
  }

  @Test
  fun `slots keep the declared wire order`() {
    assertEquals(
      listOf(
        "preplan",
        "plan",
        "implementation",
        "audit",
        "code_review",
        "quality_gate",
        "write_history",
        "commit_push",
        "pull_request",
        "monitor",
        "standalone_review",
      ),
      PhaseSlot.entries.map { it.wireValue },
    )
  }

  @Test
  fun `unknown step raises a typed error`() {
    val error = assertFailsWith<UnknownPhaseStepError> { PhaseSlot.slotForStep("deploy") }

    assertEquals("deploy", error.stepId)
  }
}
