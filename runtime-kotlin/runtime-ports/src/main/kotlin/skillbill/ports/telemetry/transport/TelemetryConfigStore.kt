package skillbill.ports.telemetry.transport

import skillbill.ports.config.model.ConfigDocumentReplaceResult
import skillbill.ports.telemetry.model.TelemetryConfigLevelWriteResult
import skillbill.ports.telemetry.model.TelemetryConfigRead
import skillbill.ports.telemetry.model.TelemetryConfigSnapshotRead
import skillbill.ports.telemetry.model.TelemetryConfigWriteRequest
import skillbill.telemetry.model.TelemetryConfigDocument
import java.nio.file.Path

interface TelemetryConfigStore {
  fun stateDir(): Path

  fun configPath(): Path

  /** Paths the runtime may select for the active machine config in this environment. */
  fun configCandidatePaths(): List<Path>

  /** Runs an operation while this store owns its config identities. */
  fun <T> withConfigTransaction(action: () -> T): T

  fun newInstallIdCandidate(): String

  fun read(): TelemetryConfigRead

  /** Reads the selected path, byte fingerprint and parsed document from one locked observation. */
  fun readSnapshot(): TelemetryConfigSnapshotRead

  fun ensure(): TelemetryConfigDocument

  /** Writes only when the selected path and bytes still match the captured request. */
  fun write(request: TelemetryConfigWriteRequest): ConfigDocumentReplaceResult

  /** Applies a level change and returns its previous value from the same locked snapshot. */
  fun mutateTelemetryLevelAtomically(level: String): TelemetryConfigLevelWriteResult
}
