package skillbill.infrastructure.sqlite.blockincident

import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecovery
import java.sql.Connection

internal class SqliteBlockIncidentStateWrites(private val connection: Connection) {
  fun recordRecoveryOutcome(
    occurrenceToken: String,
    expectedRevision: Long,
    recovery: BlockIncidentRecovery,
  ): Boolean =
    connection.prepareStatement(
      "UPDATE block_incidents SET recovery = ?, revision = revision + 1 " +
        "WHERE occurrence_token = ? AND revision = ?",
    ).use { statement ->
      statement.setString(1, recovery.wireValue)
      statement.setString(2, occurrenceToken)
      statement.setLong(3, expectedRevision)
      statement.executeUpdate() == 1
    }
}
