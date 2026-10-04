package skillbill.infrastructure.contracts.workflow.featuretask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodePhaseRecordFromArtifact
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputSourceLocation
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

class FeatureTaskRuntimePersistenceReviewPassParityTest {
  @Test
  fun `schema and model accept a remediation pass past the retired two-pass ceiling`() {
    listOf(1, 2, 3, 7, 42).forEach { pass ->
      val record = reviewRecord(pass)

      assertEquals(pass, record.reviewPassNumber, "model verdict for pass $pass")
      FeatureTaskRuntimePersistenceSchemaValidator.validate(
        record.asWorkflowArtifactEntry().toWorkflowArtifactMap(),
        "review.record",
      )
      assertEquals(
        pass,
        requireNotNull(decodePhaseRecordFromArtifact(record.asWorkflowArtifactEntry().toWorkflowArtifactMap()))
          .reviewPassNumber,
        "round-trip verdict for pass $pass",
      )
    }
  }

  @Test
  fun `schema and model agree that a pass number below one is invalid`() {
    val wireMap = reviewRecord(2).asWorkflowArtifactEntry().toWorkflowArtifactMap() + ("review_pass_number" to 0)

    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimePersistenceSchemaValidator.validate(wireMap, "review.record")
    }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      decodePhaseRecordFromArtifact(wireMap)
    }
  }

  @Test
  fun `schema accepts the launch pair the model records`() {
    val merged = reviewRecord(1).copy(launchedModel = "claude-opus-4-8[effort=high]")
    val split = reviewRecord(1).copy(launchedModel = "claude-opus-4-8", launchedEffort = "high")

    listOf(merged, split).forEach { record ->
      FeatureTaskRuntimePersistenceSchemaValidator.validate(
        record.asWorkflowArtifactEntry().toWorkflowArtifactMap(),
        "review.record",
      )
      assertEquals(
        record.launchedModel to record.launchedEffort,
        requireNotNull(decodePhaseRecordFromArtifact(record.asWorkflowArtifactEntry().toWorkflowArtifactMap()))
          .let { it.launchedModel to it.launchedEffort },
      )
    }
  }

  @Test
  fun `schema accepts duplicate-key merge repair evidence the model persists`() {
    val record =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "validate",
        status = "completed",
        attemptCount = 1,
        startedAt = "2026-08-16T12:00:00Z",
        resolvedAgentId = "cursor",
        outputArtifact = "{\"phase_id\":\"validate\"}",
        repairEvidence =
          FeatureTaskRuntimePhaseOutputRepairEvidence(
            format = FeatureTaskRuntimePhaseOutputFormat.JSON,
            originalDigest = "a".repeat(64),
            repairedDigest = "b".repeat(64),
            operation = FeatureTaskRuntimePhaseOutputRepairOperation.DEDUPLICATE_KEYS,
            sourceLocation = FeatureTaskRuntimePhaseOutputSourceLocation("validate", 12, 1, 13),
          ),
      )

    FeatureTaskRuntimePersistenceSchemaValidator.validate(
      record.asWorkflowArtifactEntry().toWorkflowArtifactMap(),
      "validate.record",
    )
    assertEquals(
      FeatureTaskRuntimePhaseOutputRepairOperation.DEDUPLICATE_KEYS,
      requireNotNull(decodePhaseRecordFromArtifact(record.asWorkflowArtifactEntry().toWorkflowArtifactMap()))
        .repairEvidence?.operation,
    )
  }

  private fun reviewRecord(pass: Int): FeatureTaskRuntimePhaseRecord =
    FeatureTaskRuntimePhaseRecord(
      phaseId = "review",
      status = "running",
      attemptCount = 1,
      startedAt = "2026-08-03T10:00:00Z",
      resolvedAgentId = "agent-review-1",
      loopId = "review_fix",
      edgeIteration = 1,
      reviewPassNumber = pass,
    )
}
