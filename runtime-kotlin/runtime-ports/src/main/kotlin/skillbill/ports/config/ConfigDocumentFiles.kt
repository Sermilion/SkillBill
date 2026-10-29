package skillbill.ports.config

import skillbill.ports.config.model.ConfigDocumentFileRead
import skillbill.ports.config.model.ConfigDocumentFormat
import skillbill.ports.config.model.ConfigDocumentReplaceRequest
import skillbill.ports.config.model.ConfigDocumentReplaceResult
import skillbill.ports.config.model.ProjectConfigLocation
import java.nio.file.Path

/** Reads and atomically replaces machine and project configuration documents. */
interface ConfigDocumentFiles {
  /** Holds cooperating config writers for the supplied file identities until [action] returns. */
  fun <T> withLocks(
    paths: Collection<Path>,
    action: () -> T,
  ): T

  /** Returns both project config locations that may participate in migration or an edit. */
  fun projectConfigLockPaths(repoRoot: Path): List<Path>

  /** Resolves the project store target and the path whose contents should be read. */
  fun projectConfigLocation(repoRoot: Path): ProjectConfigLocation

  /** Reads and parses a configuration document at [path]. */
  fun read(
    path: Path,
    format: ConfigDocumentFormat,
  ): ConfigDocumentFileRead

  /** Replaces [ConfigDocumentReplaceRequest.target] when its baseline still matches the expected fingerprint. */
  fun replace(request: ConfigDocumentReplaceRequest): ConfigDocumentReplaceResult
}
