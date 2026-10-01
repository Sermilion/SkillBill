package skillbill.workflow.taskruntime.model.handoff.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeRejectionMeasurementTest {
  @Test
  fun `the blocker's own rejection reason classifies as a length violation carrying its cap`() {
    val reason =
      "Projection validation failed: implement#produced_outputs: " +
        "\$.reconciliation_evidence.evidence: must be at most 4,096 characters long"

    assertEquals(FeatureTaskRuntimeRejectionViolationClass.LENGTH, featureTaskRuntimeRejectionViolationClassOf(reason))
    assertEquals(4096, featureTaskRuntimeRejectionCapOf(reason))
  }

  @Test
  fun `a length violation is not absorbed by the broader type phrasing it also matches`() {
    val reason = "artifact_ref: must be at most 256 characters long"

    assertEquals(FeatureTaskRuntimeRejectionViolationClass.LENGTH, featureTaskRuntimeRejectionViolationClassOf(reason))
    assertEquals(256, featureTaskRuntimeRejectionCapOf(reason))
  }

  @Test
  fun `each remaining validator phrasing lands in its own class`() {
    val cases =
      mapOf(
        "\$.produced_outputs: maxLength constraint violated" to FeatureTaskRuntimeRejectionViolationClass.LENGTH,
        "Phase output is malformed: unexpected end-of-input" to FeatureTaskRuntimeRejectionViolationClass.MALFORMED,
        "<root> must be an object." to FeatureTaskRuntimeRejectionViolationClass.MALFORMED,
        "contract_version: must be the constant value '0.1'" to FeatureTaskRuntimeRejectionViolationClass.CONST,
        "produced_outputs.projection_kind is missing" to FeatureTaskRuntimeRejectionViolationClass.MISSING,
        "property 'criterion' is not defined in the schema" to FeatureTaskRuntimeRejectionViolationClass.MISSING,
        "\$.deviations[0]: string found, object expected" to FeatureTaskRuntimeRejectionViolationClass.TYPE,
        "gap_id must be unique, duplicated [ac-002-gap-1]" to FeatureTaskRuntimeRejectionViolationClass.OTHER,
      )

    cases.forEach { (reason, expected) ->
      assertEquals(expected, featureTaskRuntimeRejectionViolationClassOf(reason), "misclassified: $reason")
    }
  }

  @Test
  fun `a reason stating no cap yields no cap rather than a fabricated one`() {
    assertNull(featureTaskRuntimeRejectionCapOf("\$.produced_outputs: maxLength constraint violated"))
    assertNull(featureTaskRuntimeRejectionCapOf("produced_outputs.projection_kind is missing"))
  }

  @Test
  fun `the emitted map carries the pointer and classification but never the offending value`() {
    val offendingValue = "the agent's verbose reconciliation narrative that overflowed the field"
    val map =
      FeatureTaskRuntimeRejectionMeasurement(
        workflowId = "wftr-20260807-123754-11fb",
        phaseId = "implement",
        iteration = 3,
        rule = "producer-projection",
        pointerPath = "/reconciliation_evidence/evidence",
        violationClass = FeatureTaskRuntimeRejectionViolationClass.LENGTH,
        declaredCap = 4096,
        observedLength = 16608,
        exhaustedFixLoop = true,
      ).toTelemetryMap()

    assertEquals("wftr-20260807-123754-11fb", map["workflow_id"])
    assertEquals("/reconciliation_evidence/evidence", map["pointer_path"])
    assertEquals("length", map["violation_class"])
    assertEquals(4096, map["declared_cap"])
    assertEquals(16608, map["observed_length"])
    assertEquals(true, map["exhausted_fix_loop"])
    assertFalse(
      map.values.any { it is String && it.contains(offendingValue) },
      "the rejection event must never carry the rejected payload",
    )
  }

  @Test
  fun `optional measures are omitted rather than emitted as nulls`() {
    val map =
      FeatureTaskRuntimeRejectionMeasurement(
        workflowId = "wf-1",
        phaseId = "audit",
        iteration = 1,
        rule = "phase-output-schema",
        pointerPath = "/",
        violationClass = FeatureTaskRuntimeRejectionViolationClass.MALFORMED,
      ).toTelemetryMap()

    assertFalse(map.containsKey("declared_cap"), "an unstated cap is absent, not null")
    assertFalse(map.containsKey("observed_length"), "an unmeasured length is absent, not null")
    assertTrue(map.containsKey("violation_class"), "the classification is always present")
  }

  @Test
  fun `identity and counter fields loud-fail rather than emitting an unattributable row`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeRejectionMeasurement(
        workflowId = " ",
        phaseId = "implement",
        iteration = 1,
        rule = "producer-projection",
        pointerPath = "/",
        violationClass = FeatureTaskRuntimeRejectionViolationClass.LENGTH,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeRejectionMeasurement(
        workflowId = "wf-1",
        phaseId = "implement",
        iteration = 0,
        rule = "producer-projection",
        pointerPath = "/",
        violationClass = FeatureTaskRuntimeRejectionViolationClass.LENGTH,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeRejectionMeasurement(
        workflowId = "wf-1",
        phaseId = "implement",
        iteration = 1,
        rule = "producer-projection",
        pointerPath = "/",
        violationClass = FeatureTaskRuntimeRejectionViolationClass.LENGTH,
        observedLength = -1,
      )
    }
  }
}
