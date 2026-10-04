package skillbill.workflow.taskruntime.model.handoff.task

import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDiagnosticFailureClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimeDiagnosticDegradationMeasurementTest {
  @Test
  fun `toTelemetryMap emits exactly the declared content-free keys including contract version 0_1`() {
    val sentinel = "AGENT_OUTPUT /tmp/metrics.db PROMPT_TEXT PROCESS_OUTPUT"
    val map =
      FeatureTaskRuntimeDiagnosticDegradationMeasurement(
        workflowId = "wftr-20260812-201951-o64o",
        phaseId = "validate",
        attempt = 1,
        repairTurn = 2,
        generation = 0,
        operation = "retain-producer-output",
        failureClass = FeatureTaskRuntimeDiagnosticFailureClass.CONFLICT,
        conflictingKey = "wftr-20260812-201951-o64o:validate:0:1:2:cursor",
      ).toTelemetryMap()

    assertEquals("0.1", map["contract_version"])
    assertEquals(
      setOf(
        "contract_version",
        "workflow_id",
        "phase_id",
        "attempt",
        "generation",
        "operation",
        "failure_class",
        "conflicting_key",
        "repair_turn",
      ),
      map.keys,
    )
    assertEquals("conflict", map["failure_class"])
    assertEquals(2, map["repair_turn"])
    assertTrue(
      map.values.none { it is String && it.contains(sentinel) },
      "the degradation event must never carry agent output, prompt text, a database path, or process output",
    )
  }

  @Test
  fun `repair_turn is omitted when null rather than emitted as null`() {
    val map =
      FeatureTaskRuntimeDiagnosticDegradationMeasurement(
        workflowId = "wf-1",
        phaseId = "implement",
        attempt = 1,
        repairTurn = null,
        generation = 0,
        operation = "read-producer-output",
        failureClass = FeatureTaskRuntimeDiagnosticFailureClass.PERSISTENCE,
        conflictingKey = "wf-1:implement:0:1:*:cursor",
      ).toTelemetryMap()

    assertFalse(map.containsKey("repair_turn"), "an unscoped repair turn is absent, not null")
    assertTrue(map.containsKey("failure_class"), "the failure class is always present")
  }
}
