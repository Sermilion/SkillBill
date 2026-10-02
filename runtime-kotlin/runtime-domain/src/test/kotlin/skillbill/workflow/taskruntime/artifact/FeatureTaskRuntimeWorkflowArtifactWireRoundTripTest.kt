package skillbill.workflow.taskruntime.artifact

import skillbill.contracts.JsonCodec
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairOutcome
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceiptEntry
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.audit.QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffEnvelope
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeCheckpointIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class FeatureTaskRuntimeWorkflowArtifactWireRoundTripTest {
  @Test
  fun `handoff envelope wire round-trips through the public artifact seam`() {
    val envelope = FeatureTaskRuntimeHandoffEnvelope(consumerPhaseId = "implement")
    val wire = envelope.asWorkflowArtifactEntry()
    val decoded = decodeHandoffEnvelopeFromArtifact(assertNotNull(JsonCodec.anyToStringAnyMap(wire)))
    assertEquals(
      JsonCodec.anyToStringAnyMap(wire),
      JsonCodec.anyToStringAnyMap(decoded.asWorkflowArtifactEntry()),
    )
  }

  @Test
  fun `repair receipt wire round-trips through the public artifact seam`() {
    val receipt =
      FeatureTaskRuntimeRepairReceipt(
        roundNumber = 1,
        preFixCheckpointSha = "a".repeat(40),
        entries =
          listOf(
            FeatureTaskRuntimeRepairReceiptEntry(
              outcome = FeatureTaskRuntimeRepairOutcome.NO_EDIT_REQUIRED,
              findingId = "F-1",
              noEditReason = "already satisfied",
            ),
          ),
      )
    val wire = receipt.asWorkflowArtifactEntry()
    val decoded = decodeRepairReceiptFromArtifact(assertNotNull(JsonCodec.anyToStringAnyMap(wire)), "repair_receipt")
    assertEquals(
      JsonCodec.anyToStringAnyMap(wire),
      JsonCodec.anyToStringAnyMap(decoded.asWorkflowArtifactEntry()),
    )
  }

  @Test
  fun `quarantine record wire round-trips through the public artifact seam`() {
    val entry =
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
    val wire = listOf(entry).asQuarantineWorkflowArtifactEntry()
    val decoded = decodeQuarantineEntriesFromArtifact(wire)
    assertEquals(listOf(entry), decoded)
    assertEquals(
      JsonCodec.anyToStringAnyMap(wire),
      JsonCodec.anyToStringAnyMap(listOf(decoded.single()).asQuarantineWorkflowArtifactEntry()),
    )
  }

  @Test
  fun `checkpoint identities wire round-trips through the public artifact seam`() {
    val identity =
      FeatureTaskRuntimeCheckpointIdentity(
        sequenceNumber = 0,
        issueKey = "SKILL-52",
        subtaskId = "1",
        checkpointRef = "refs/skill-bill/checkpoints/SKILL-52/1/0",
        branch = "feat/SKILL-52",
        phaseId = "implement",
        generation = 1,
        ownedPathDigest = "b".repeat(64),
        ownedPathCount = 1,
        commitSha = "c".repeat(40),
        recordedAt = "2026-01-01T00:00:00Z",
        loopId = "loop-1",
        parentSha = "d".repeat(40),
      )
    val wire = listOf(identity).asCheckpointIdentitiesArtifactEntry()
    val decoded = decodeCheckpointIdentitiesFromArtifact(wire)
    assertEquals(listOf(identity), decoded)
    assertEquals(
      JsonCodec.anyToStringAnyMap(wire),
      JsonCodec.anyToStringAnyMap(listOf(decoded.single()).asCheckpointIdentitiesArtifactEntry()),
    )
  }

  @Test
  fun `malformed workflow artifacts fail instead of being treated as absent`() {
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      phaseRecordsFromWorkflowArtifacts(listOf("not an artifact object"))
    }
  }
}
