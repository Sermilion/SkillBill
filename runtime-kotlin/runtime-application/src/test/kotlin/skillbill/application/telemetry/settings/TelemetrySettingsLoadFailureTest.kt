package skillbill.application.telemetry.settings

import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.telemetry.model.TelemetrySettingsLoad
import skillbill.ports.telemetry.transport.TelemetrySettingsProvider
import skillbill.telemetry.model.TelemetrySettings
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TelemetrySettingsLoadFailureTest {
  @Test
  fun `a failing load emits one diagnostic and returns the disabled fallback`() {
    val diagnostics = RecordingRuntimeDiagnostics()
    val provider =
      object : TelemetrySettingsProvider {
        override fun load(materialize: Boolean): TelemetrySettings = error("config unreadable")

        override fun loadOrUnavailable(materialize: Boolean): TelemetrySettingsLoad =
          TelemetrySettingsLoad.Unavailable("config unreadable")
      }

    val settings = telemetrySettingsOrNull(provider, diagnostics)

    assertNull(settings)
    assertEquals(1, diagnostics.errors.size)
    assertEquals(TELEMETRY_SETTINGS_LOAD_FAILURE_MESSAGE, diagnostics.errors.single().message)
    assertEquals("config unreadable", diagnostics.errors.single().cause?.message)
  }

  @Test
  fun `cooperative cancellation still propagates from optional telemetry loading`() {
    val cancelled =
      object : TelemetrySettingsProvider {
        override fun load(materialize: Boolean) = throw CancellationException("cancelled")

        override fun loadOrUnavailable(materialize: Boolean) = throw CancellationException("cancelled")
      }
    val interrupted =
      object : TelemetrySettingsProvider {
        override fun load(materialize: Boolean) = throw InterruptedException("interrupted")

        override fun loadOrUnavailable(materialize: Boolean) = throw InterruptedException("interrupted")
      }
    val diagnostics = RecordingRuntimeDiagnostics()

    assertFailsWith<CancellationException> { telemetrySettingsOrNull(cancelled, diagnostics) }
    assertFailsWith<InterruptedException> { telemetrySettingsOrNull(interrupted, diagnostics) }
    assertEquals(0, diagnostics.errors.size)
  }
}

private class RecordingRuntimeDiagnostics : RuntimeDiagnostics {
  data class Record(val message: String, val cause: Throwable?)

  val errors = mutableListOf<Record>()

  override fun warning(
    message: String,
    error: Throwable?,
  ) = Unit

  override fun error(
    message: String,
    error: Throwable?,
  ) {
    errors += Record(message, error)
  }
}
