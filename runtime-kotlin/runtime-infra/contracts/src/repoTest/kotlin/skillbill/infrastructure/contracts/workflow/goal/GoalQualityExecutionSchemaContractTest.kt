package skillbill.infrastructure.contracts.workflow.goal

import skillbill.contracts.workflow.goal.GOAL_QUALITY_EXECUTION_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GoalQualityExecutionPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.quality.GoalQualityReadiness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoalQualityExecutionSchemaContractTest {
  @Test
  fun `bundled quality schema loads current readiness and rejects an unsupported version`() {
    val payload =
      mapOf(
        GoalQualityExecutionPayloadKeys.CONTRACT_VERSION to GOAL_QUALITY_EXECUTION_CONTRACT_VERSION,
        GoalQualityExecutionPayloadKeys.READINESS to GoalQualityReadiness.CLEAN_UNSTARTED.wireValue,
        GoalQualityExecutionPayloadKeys.EXECUTION to null,
      )

    GoalQualityExecutionSchemaValidator.validate(payload, "current readiness")

    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        GoalQualityExecutionSchemaValidator.validate(
          payload + (GoalQualityExecutionPayloadKeys.CONTRACT_VERSION to "999.0"),
          "unsupported readiness",
        )
      }
    assertEquals(WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION, failure.code)
  }
}
