package skillbill.engine.goalrunner.planning.context

import kotlin.test.Test
import kotlin.test.assertFailsWith
import skillbill.error.core.SkillBillRuntimeException

class GoalPlanningSharedContextPacketTypedErrorTest {
  @Test
  fun `malformed shared context catalog raises the planning schema error`() {
    assertFailsWith<SkillBillRuntimeException> {
      GoalPlanningSharedContextPacketValidation.requireValidCatalog("not-an-object")
    }
  }

  @Test
  fun `unsupported shared context version raises the planning schema error`() {
    assertFailsWith<SkillBillRuntimeException> {
      GoalPlanningSharedContextPacket.migrate(
        mapOf(GoalPlanningSharedContextPacketPayloadKeys.PACKET_VERSION to "0.9"),
      )
    }
  }
}
