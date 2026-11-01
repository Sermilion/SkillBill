package skillbill.infrastructure.sqlite.core.schema

import skillbill.ports.filesystem.model.DatabaseFileObservation
import java.nio.file.Path
import java.sql.Connection

internal data class OpenDatabase(
  internal val connection: Connection,
  internal val dbPath: Path,
  internal val fileObservation: DatabaseFileObservation,
) : AutoCloseable {
  override fun close() {
    connection.close()
  }
}
