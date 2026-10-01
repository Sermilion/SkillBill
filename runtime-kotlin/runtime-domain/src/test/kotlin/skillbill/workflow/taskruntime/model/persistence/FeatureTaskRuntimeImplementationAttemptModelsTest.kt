package skillbill.workflow.taskruntime.model.persistence

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPT_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimeImplementationAttemptModelsTest {
  @Test
  fun `history orders by sequence number regardless of append order`() {
    val history =
      listOf(attempt(sequenceNumber = 3), attempt(sequenceNumber = 1))
        .fold(emptyList<FeatureTaskRuntimeImplementationAttempt>()) { acc, entry ->
          featureTaskRuntimeAppendImplementationAttempt(acc, entry)
        }

    val appended = featureTaskRuntimeAppendImplementationAttempt(history, attempt(sequenceNumber = 2))

    assertEquals(listOf(1, 2, 3), appended.map { it.sequenceNumber })
  }

  @Test
  fun `pruning at the limit drops only closed attempts while an open obligation is retained`() {
    val open = attempt(sequenceNumber = 1, status = FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE)
    val closedA = attempt(sequenceNumber = 2, status = FeatureTaskRuntimeImplementationAttemptStatus.COMPLETED)
    val closedB = attempt(sequenceNumber = 3, status = FeatureTaskRuntimeImplementationAttemptStatus.COMPLETED)

    val pruned =
      featureTaskRuntimeAppendImplementationAttempt(
        existing = listOf(open, closedA, closedB),
        entry = attempt(sequenceNumber = 4, status = FeatureTaskRuntimeImplementationAttemptStatus.COMPLETED),
        retentionLimit = 3,
      )

    assertEquals(3, pruned.size)
    assertTrue(pruned.any { it.sequenceNumber == 1 }, "The open-obligation attempt must survive pruning.")
    assertEquals(listOf(1, 3, 4), pruned.map { it.sequenceNumber })
  }

  @Test
  fun `pruning falls back to oldest-first when every retained attempt owes work`() {
    val open = (1..3).map { attempt(sequenceNumber = it) }

    val pruned = featureTaskRuntimeAppendImplementationAttempt(open, attempt(sequenceNumber = 4), retentionLimit = 3)

    assertEquals(listOf(2, 3, 4), pruned.map { it.sequenceNumber })
  }

  @Test
  fun `wire round-trip preserves value prompt and loop context`() {
    val entry =
      attempt(
        sequenceNumber = 7,
        status = FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE,
      ).copy(
        loopId = "audit_gap",
        edgeIteration = 2,
        prompt = "optional directive",
        failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
      )

    val decoded =
      featureTaskRuntimeImplementationAttemptsFromWire(
        featureTaskRuntimeImplementationAttemptRecordToWire(listOf(entry)),
      )

    assertEquals(listOf(entry), decoded)
  }

  @Test
  fun `decode rejects an unsupported contract version`() {
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      featureTaskRuntimeImplementationAttemptsFromWire(
        mapOf("contract_version" to "0.9", "attempts" to emptyList<Any?>()),
      )
    }
  }

  @Test
  fun `decode rejects a record that is not an object`() {
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      featureTaskRuntimeImplementationAttemptsFromWire(listOf<Any?>())
    }
  }

  @Test
  fun `decode rejects an unknown attempt field rather than silently dropping it`() {
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      featureTaskRuntimeImplementationAttemptsFromWire(
        mapOf(
          "contract_version" to FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPT_CONTRACT_VERSION,
          "attempts" to listOf(attempt().toArtifactMap() + mapOf("completed" to true)),
        ),
      )
    }
  }

  @Test
  fun `only an incomplete attempt carries an open obligation`() {
    assertTrue(
      attempt(status = FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE).carriesOpenObligation,
    )
    assertFalse(
      attempt(status = FeatureTaskRuntimeImplementationAttemptStatus.COMPLETED).carriesOpenObligation,
    )
  }

  private fun attempt(
    sequenceNumber: Int = 1,
    status: FeatureTaskRuntimeImplementationAttemptStatus =
      FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE,
  ): FeatureTaskRuntimeImplementationAttempt =
    FeatureTaskRuntimeImplementationAttempt(
      sequenceNumber = sequenceNumber,
      phaseId = "implement",
      attemptNumber = 1,
      agentId = "claude",
      status = status,
      recordedAt = "2026-08-04T10:00:00Z",
      value = "segment-$sequenceNumber prose",
    )
}
