package skillbill.workflow.taskruntime.phase.task

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_VERSION
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputSourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FeatureTaskRuntimePhaseOutputValidationModelsTest {
  @Test
  fun `repair evidence is versioned payload free and location aware`() {
    val evidence =
      FeatureTaskRuntimePhaseOutputRepairEvidence(
        format = FeatureTaskRuntimePhaseOutputFormat.YAML,
        originalDigest = "0".repeat(64),
        repairedDigest = "1".repeat(64),
        operation = FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER,
        sourceLocation = FeatureTaskRuntimePhaseOutputSourceLocation("plan", 0, 3, 4),
      )

    assertEquals(FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_VERSION, evidence.contractVersion)
    assertEquals(FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_VERSION, evidence.validatorVersion)
    assertEquals(FeatureTaskRuntimePhaseOutputFormat.YAML, evidence.format)
    assertEquals(FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER, evidence.operation)
    assertEquals(3, evidence.sourceLocation.line)
    assertEquals(4, evidence.sourceLocation.column)
    assertFalse(evidence.toString().contains("payload"))
  }

  @Test
  fun `repair evidence round trips through the artifact map and rejects unknown fields`() {
    val evidence =
      FeatureTaskRuntimePhaseOutputRepairEvidence(
        format = FeatureTaskRuntimePhaseOutputFormat.JSON,
        originalDigest = "a".repeat(64),
        repairedDigest = "b".repeat(64),
        operation = FeatureTaskRuntimePhaseOutputRepairOperation.REMOVE_EXTRA_CLOSING_DELIMITER,
        sourceLocation = FeatureTaskRuntimePhaseOutputSourceLocation("plan", 5, 1, 6),
      )

    assertEquals(evidence, FeatureTaskRuntimePhaseOutputRepairEvidence.fromArtifactMap(evidence.toArtifactMap()))
    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputRepairEvidence.fromArtifactMap(evidence.toArtifactMap() + ("unexpected" to true))
    }
  }
}
