package skillbill.ports.telemetry.transport

import skillbill.ports.telemetry.model.TelemetryConfigRead
import skillbill.telemetry.model.TelemetryConfigDocument
import skillbill.telemetry.telemetryLevels
import skillbill.telemetry.withTelemetryLevel
import java.nio.file.Path

interface TelemetryConfigStore {
  fun stateDir(): Path

  fun configPath(): Path

  fun read(): TelemetryConfigRead

  fun ensure(): TelemetryConfigDocument

  fun write(document: TelemetryConfigDocument)
}

fun TelemetryConfigStore.writeTelemetryLevel(level: String): Boolean {
  val document =
    if (level == telemetryLevels.first()) {
      when (val read = read()) {
        TelemetryConfigRead.Absent -> return false
        is TelemetryConfigRead.Malformed -> throw IllegalArgumentException(read.reason)
        is TelemetryConfigRead.Present -> read.document
      }
    } else {
      ensure()
    }
  write(document.withTelemetryLevel(level, configPath().toString()))
  return true
}
