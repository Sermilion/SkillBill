package skillbill.engine.featuretask.slot.qualitygate.agentvalidate

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.ValidationTestProbePayloadKeys
import skillbill.engine.featuretask.slot.qualitygate.ValidateProbeActivation
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidateProbeSettlementTest {
  private val diagnostics = RecordingDiagnostics()
  private val site = ValidateProbeSettlement.Site(diagnostics, WORKFLOW_ID, PHASE_ID)

  @Test
  fun `a prose report without probe lines stores an empty list with a warning and leaves the verdict untouched`() {
    val settled = ValidateProbeSettlement.storing(completion(MALFORMED_PROSE), MALFORMED_PROSE, site)

    assertEquals(emptyList<Any?>(), storedProbes(settled))
    assertEquals(1, diagnostics.warnings.size)
    assertTrue(PHASE_ID in diagnostics.warnings.single())
    assertEquals(COMPLETED, settled.envelopeWireMap()[SharedPayloadKeys.STATUS])
  }

  @Test
  fun `a survived probe is stored as survived and not repaired without blocking`() {
    val settled = ValidateProbeSettlement.storing(completion(SURVIVED_PROSE), SURVIVED_PROSE, site)

    val stored = storedProbes(settled).single() as Map<*, *>
    assertEquals("survived", stored[ValidationTestProbePayloadKeys.RESULT])
    assertEquals(false, stored[ValidationTestProbePayloadKeys.REPAIRED])
    assertEquals(COMPLETED, settled.envelopeWireMap()[SharedPayloadKeys.STATUS])
    assertTrue(diagnostics.warnings.isEmpty())
  }

  @Test
  fun `not_run probes are kept in order and raise an unavailable-probe warning`() {
    val prose = "$SURVIVED_PROSE\nTest probe other: not_run guards G2 repaired no reason no targeted command"

    val settled = ValidateProbeSettlement.storing(completion(prose), prose, site)

    val refs = storedProbes(settled).map { (it as Map<*, *>)[ValidationTestProbePayloadKeys.TEST_REF] }
    assertEquals(listOf("CartTest.adds", "other"), refs)
    assertEquals(1, diagnostics.warnings.size)
    assertTrue("other" in diagnostics.warnings.single())
  }

  @Test
  fun `only a goal-child run owns probes and gate triage never does`() {
    assertTrue(ValidateProbeActivation.applies(SkeletonDefinition.GOAL_CHILD, validationGateTriage = false))
    assertFalse(ValidateProbeActivation.applies(SkeletonDefinition.GOAL_CHILD, validationGateTriage = true))
    SkeletonDefinition.entries.filter { it.id != SkeletonDefinition.GOAL_CHILD.id }.forEach { other ->
      assertFalse(ValidateProbeActivation.applies(other, validationGateTriage = false), other.id)
    }
  }

  private fun storedProbes(output: NormalizedFeatureTaskRuntimePhaseOutput): List<*> {
    val produced = JsonCodec.anyToStringAnyMap(output.envelopeWireMap()[SharedPayloadKeys.PRODUCED_OUTPUTS])
    return produced.orEmpty()[ValidationTestProbePayloadKeys.TEST_PROBES] as List<*>
  }

  private fun completion(prose: String): NormalizedFeatureTaskRuntimePhaseOutput =
    NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        mapOf(
          "phase_id" to PHASE_ID,
          "contract_version" to "1.0",
          SharedPayloadKeys.STATUS to COMPLETED,
          "summary" to "done",
          SharedPayloadKeys.PRODUCED_OUTPUTS to mapOf(SharedPayloadKeys.VALUE to prose),
        ),
      ),
    )

  private class RecordingDiagnostics : RuntimeDiagnostics {
    val warnings = mutableListOf<String>()

    override fun warning(
      message: String,
      error: Throwable?,
    ) {
      warnings += message
    }

    override fun error(
      message: String,
      error: Throwable?,
    ) = Unit
  }

  private companion object {
    const val WORKFLOW_ID = "wftr-test"
    const val PHASE_ID = "validate"
    const val COMPLETED = "completed"
    const val MALFORMED_PROSE = "All checks passed and nothing else to report."
    const val SURVIVED_PROSE =
      "Test probe CartTest.adds: survived guards Cart.kt:12 repaired no reason still passes without the guard"
  }
}
