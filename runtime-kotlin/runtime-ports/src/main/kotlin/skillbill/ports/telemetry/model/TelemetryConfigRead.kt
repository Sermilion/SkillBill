package skillbill.ports.telemetry.model

import skillbill.telemetry.model.TelemetryConfigDocument

sealed interface TelemetryConfigRead {
  data object Absent : TelemetryConfigRead

  data class Malformed(val reason: String) : TelemetryConfigRead

  data class Present(val document: TelemetryConfigDocument) : TelemetryConfigRead
}
