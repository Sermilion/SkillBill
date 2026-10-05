package skillbill.ports.telemetry.transport

import skillbill.ports.telemetry.model.TelemetrySettingsLoad
import skillbill.telemetry.model.TelemetrySettings

interface TelemetrySettingsProvider {
  fun load(materialize: Boolean = false): TelemetrySettings

  fun loadOrUnavailable(materialize: Boolean = false): TelemetrySettingsLoad
}
