package skillbill.engine.featuretask.slot.audit

import skillbill.contracts.SharedPayloadKeys
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AcceptanceAuditVerdictRuleTest {
  @Test
  fun `an unknown audit verdict word settles to the default and emits a diagnostics record`() {
    val diagnostics = RecordingDiagnostics()
    val output =
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        mapOf(
          SharedPayloadKeys.STATUS to "completed",
          SharedPayloadKeys.VERDICT to "maybe",
          SharedPayloadKeys.PRODUCED_OUTPUTS to mapOf(SharedPayloadKeys.VALUE to "AC-003: export is not implemented."),
        ),
      )

    val rule = AcceptanceAuditVerdictRule(diagnostics)
    val verdict = rule.verdictFor(FeatureTaskRuntimeVerdict("maybe"), output)
    rule.verdictFor(FeatureTaskRuntimeVerdict("maybe"), output)

    assertEquals(AcceptanceAuditVerdictRule.UNKNOWN_WORD_DEFAULT, verdict)
    assertEquals(1, diagnostics.warnings.size)
    assertTrue(diagnostics.warnings.single().contains("'maybe'"))
  }

  @Test
  fun `an absent audit verdict word settles to the default and emits a diagnostics record`() {
    val diagnostics = RecordingDiagnostics()
    val output =
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        mapOf(
          SharedPayloadKeys.STATUS to "completed",
          SharedPayloadKeys.PRODUCED_OUTPUTS to mapOf(SharedPayloadKeys.VALUE to "AC-003: export is not implemented."),
        ),
      )

    val verdict = AcceptanceAuditVerdictRule(diagnostics).verdictFor(null, output)

    assertEquals(AcceptanceAuditVerdictRule.UNKNOWN_WORD_DEFAULT, verdict)
    assertEquals(1, diagnostics.warnings.size)
  }
}

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
