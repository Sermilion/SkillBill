package skillbill.engine.featuretask.slot.writehistory

import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeMeasuredFactKeys
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.ports.diagnostics.RuntimeDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WriteHistoryMeasurementTest {
  @Test
  fun `the facts come from the paths the step introduced`() {
    val diagnostics = RecordingDiagnostics()
    val manifest =
      FeatureTaskRuntimePhaseFileManifest(
        before = listOf("src/Foo.kt"),
        after = listOf("runtime/agent/history.md", "src/Foo.kt"),
      )

    val facts = WriteHistoryMeasurement(diagnostics).facts(manifest)

    assertEquals(
      mapOf(
        FeatureTaskRuntimeMeasuredFactKeys.CHANGED_PATHS to listOf("runtime/agent/history.md"),
        FeatureTaskRuntimeMeasuredFactKeys.HISTORY_WRITTEN to true,
        FeatureTaskRuntimeMeasuredFactKeys.DECISIONS_RECORDED to false,
      ),
      facts,
    )
    assertTrue(diagnostics.warnings.isEmpty())
  }

  @Test
  fun `a file already dirty before the step is unknown and emits a diagnostics record`() {
    val diagnostics = RecordingDiagnostics()
    val manifest =
      FeatureTaskRuntimePhaseFileManifest(
        before = listOf(WriteHistoryMeasurement.DECISIONS_FILE),
        after = listOf(WriteHistoryMeasurement.DECISIONS_FILE, WriteHistoryMeasurement.HISTORY_FILE),
      )

    val facts = WriteHistoryMeasurement(diagnostics).facts(manifest)

    assertEquals(true, facts[FeatureTaskRuntimeMeasuredFactKeys.HISTORY_WRITTEN])
    assertEquals(
      FeatureTaskRuntimeMeasuredFactKeys.UNKNOWN,
      facts[FeatureTaskRuntimeMeasuredFactKeys.DECISIONS_RECORDED],
    )
    assertEquals(1, diagnostics.warnings.size)
    assertTrue(diagnostics.warnings.single().contains(WriteHistoryMeasurement.DECISIONS_FILE))
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
