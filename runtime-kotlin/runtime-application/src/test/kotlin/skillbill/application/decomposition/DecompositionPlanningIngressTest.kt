package skillbill.application.decomposition

import skillbill.contracts.decomposition.DecompositionPlanningResult
import skillbill.error.core.SkillBillRuntimeException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class DecompositionPlanningIngressTest {
  @Test
  fun `typed planning ingress rejects empty subtasks`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        parseSubtasks(
          DecompositionPlanningResult(
            mode = "decompose",
            subtasks = emptyList(),
          ),
          "typed-planning-result",
        )
      }
    assertContains(error.message.orEmpty(), "at least one subtask")
  }
}
