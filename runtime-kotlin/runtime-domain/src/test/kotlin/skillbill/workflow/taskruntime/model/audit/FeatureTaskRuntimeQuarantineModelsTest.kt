package skillbill.workflow.taskruntime.model.audit

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.workflow.taskruntime.model.core.FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FeatureTaskRuntimeQuarantineModelsTest {
  @Test
  fun `an unsupported field loud-fails rather than being dropped on decode`() {
    val valid = featureTaskRuntimeQuarantineRecordToWire(listOf(identityEntry()))
    val envelopeError =
      assertFailsWith<SkillBillRuntimeException> {
        featureTaskRuntimeQuarantineEntriesFromWire(valid + ("unexpected" to true))
      }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }
    val leaked = listOf(identityEntry().toArtifactMap() + ("leaked_body" to "secret"))
    val entryError =
      assertFailsWith<SkillBillRuntimeException> {
        featureTaskRuntimeQuarantineEntriesFromWire(valid + ("entries" to leaked))
      }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }
    listOf(envelopeError, entryError).forEach { error ->
      assertFalse(
        error.message.orEmpty().contains("secret"),
        "decode errors must not carry undeclared field values",
      )
    }
    assertContains(envelopeError.message.orEmpty(), "unexpected")
    assertContains(entryError.message.orEmpty(), "leaked_body")
  }

  @Test
  fun `an unsupported contract version loud-fails so the store is not rewritten`() {
    val valid = featureTaskRuntimeQuarantineRecordToWire(listOf(identityEntry()))
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        featureTaskRuntimeQuarantineEntriesFromWire(valid + ("contract_version" to "0.2"))
      }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }
    assertContains(error.message.orEmpty(), "unsupported contract version")
    assertContains(error.message.orEmpty(), FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE)
  }

  @Test
  fun `diagnostic_degraded false is rejected rather than loaded as an unmarked identity entry`() {
    val wire = featureTaskRuntimeQuarantineRecordToWire(listOf(identityEntry()))
    val markedFalse = listOf(identityEntry().toArtifactMap() + ("diagnostic_degraded" to false))
    assertFailsWith<SkillBillRuntimeException> {
      featureTaskRuntimeQuarantineEntriesFromWire(wire + ("entries" to markedFalse))
    }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }
  }

  private fun identityEntry() =
    FeatureTaskRuntimeQuarantineEntry(
      producingPhaseId = "plan",
      consumingPhaseId = "implement",
      producingIteration = 1,
      rejectionClass = QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION,
      rejectionDetail = "plan#produced_outputs: projection_kind is missing",
      regenerationAttempt = 1,
      quarantinedAtIteration = 1,
      diagnosticIdentity = "rod_prechange",
      rejectedRecordByteSize = 11,
      rejectedRecordSha256 = "a".repeat(64),
    )
}
