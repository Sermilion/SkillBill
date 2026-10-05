package skillbill.ports.telemetry.model

import skillbill.telemetry.model.TelemetrySettings

sealed interface TelemetrySettingsLoad {
  data class Loaded(val settings: TelemetrySettings) : TelemetrySettingsLoad

  data class Unavailable(val reason: String) : TelemetrySettingsLoad
}
