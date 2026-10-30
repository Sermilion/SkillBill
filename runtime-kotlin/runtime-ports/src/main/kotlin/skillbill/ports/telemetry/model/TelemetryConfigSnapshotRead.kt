package skillbill.ports.telemetry.model

import skillbill.ports.config.model.ConfigFingerprint
import skillbill.telemetry.model.TelemetryConfigDocument
import java.nio.file.Path

sealed interface TelemetryConfigSnapshotRead {
  val path: Path

  data class Absent(override val path: Path) : TelemetryConfigSnapshotRead

  data class Malformed(
    override val path: Path,
    val fingerprint: ConfigFingerprint,
    val reason: String,
  ) : TelemetryConfigSnapshotRead

  data class Present(
    override val path: Path,
    val fingerprint: ConfigFingerprint,
    val document: TelemetryConfigDocument,
  ) : TelemetryConfigSnapshotRead
}

data class TelemetryConfigWriteRequest(
  val path: Path,
  val fingerprint: ConfigFingerprint?,
  val document: TelemetryConfigDocument,
)
