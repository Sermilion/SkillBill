package skillbill.workflow.taskruntime.artifact

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

class AcceptanceCriterionRepairAccountingDecodeTest {
  @Test
  fun `durable counters and revisions cannot truncate or wrap into an admissible state`() {
    val state = admittedState()
    val wire = state.asRepairWireMap()
    val criterion = assertNotNull((wire[Keys.CRITERIA] as? List<*>)?.single() as? Map<*, *>)
    val corruptions =
      listOf(
        wire + (Keys.REVISION to 0.5),
        wire + (Keys.REVISION to Long.MAX_VALUE.toDouble()),
        wire + (Keys.UNSUCCESSFUL_TOTALS to mapOf("AC-001" to 4_294_967_296L)),
        wire + (Keys.CRITERIA to listOf(criterion + (Keys.ORDINAL to 4_294_967_297L))),
      )
    assertEquals(state, wire.toAcceptanceCriterionRepairState("valid"))
    for (corrupt in corruptions) {
      assertInvalid(corrupt)
    }
  }

  @Test
  fun `oversized accounting fails before durable restoration`() {
    val state = admittedState()
    val oversized =
      state.copy(
        criteria =
          state.criteria.map {
            it.copy(evidenceRef = "x".repeat(AcceptanceCriterionRepairState.MAX_DURABLE_UTF8_BYTES))
          },
      )
    assertInvalid(oversized.asRepairWireMap())
  }

  private fun assertInvalid(wire: Map<String, Any?>) {
    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        FeatureTaskRuntimeWorkflowArtifactMap.from(wire).toAcceptanceCriterionRepairState("corrupt")
      }
    assertEquals(FeatureTaskRuntimeFailureCode.INVALID_ACCEPTANCE_CRITERION_REPAIR_STATE_SCHEMA, failure.code)
  }

  private fun admittedState(): AcceptanceCriterionRepairState =
    assertNotNull(
      AcceptanceCriterionRepairState.admit(
        AcceptanceCriterionRepairAdmission(
          "catalog",
          "execution",
          "a".repeat(64),
          listOf("first"),
          listOf(
            AcceptanceCriterionRepairCriterion(
              "AC-001",
              null,
              "first",
              1,
              AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
              AcceptanceCriterionValidationObligation.DEFERRED,
              true,
              "audit:1",
            ),
          ),
          emptyList(),
        ),
      ),
    )
}
