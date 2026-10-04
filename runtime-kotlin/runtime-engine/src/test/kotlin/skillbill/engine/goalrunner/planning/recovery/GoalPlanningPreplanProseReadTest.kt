package skillbill.engine.goalrunner.planning.recovery

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoalPlanningPreplanProseReadTest {
  @Test
  fun `a payload that is not a JSON object fails typed at the root`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        preplanProseValueHash("shared-preplan-discarded")
      }

    assertEquals(InstallFailureCode.INVALID_GOAL_PLANNING_PREPARATION_SCHEMA, error.code)
    assertContains(error.message.orEmpty(), "at '<root>'")
  }

  @Test
  fun `produced_outputs that is not an object fails typed at produced_outputs`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        preplanProseValueHash("""{"phase_id":"preplan","produced_outputs":"not-an-object"}""")
      }

    assertEquals(InstallFailureCode.INVALID_GOAL_PLANNING_PREPARATION_SCHEMA, error.code)
    assertContains(error.message.orEmpty(), "at 'produced_outputs'")
  }

  @Test
  fun `a missing value fails typed at produced_outputs value`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        preplanProseValueHash("""{"phase_id":"preplan","produced_outputs":{"prompt":"only"}}""")
      }

    assertEquals(InstallFailureCode.INVALID_GOAL_PLANNING_PREPARATION_SCHEMA, error.code)
    assertContains(error.message.orEmpty(), "at 'produced_outputs.value'")
  }

  @Test
  fun `an absent prompt still reads as null`() {
    assertEquals(
      null,
      preplanProsePrompt("""{"phase_id":"preplan","produced_outputs":{"value":"prose","prompt":"  "}}"""),
    )
  }
}
