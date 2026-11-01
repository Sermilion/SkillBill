package skillbill.engine.goalrunner.execution.support

import org.junit.jupiter.api.Test
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.AgentAddonFailureCode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoalRunnerWorkflowFamilyLookupDecodeTest {
  @Test
  fun `malformed agent addon selection raises typed error`() {
    val notAList =
      assertFailsWith<SkillBillRuntimeException> {
        decodeGoalAgentAddonSelection("not-a-list")
      }
    val invalidEntry =
      assertFailsWith<SkillBillRuntimeException> {
        decodeGoalAgentAddonSelection(listOf(mapOf("slug" to "only-slug")))
      }
    assertEquals(AgentAddonFailureCode.INVALID_SELECTION, notAList.code)
    assertEquals(AgentAddonFailureCode.INVALID_SELECTION, invalidEntry.code)
  }
}
