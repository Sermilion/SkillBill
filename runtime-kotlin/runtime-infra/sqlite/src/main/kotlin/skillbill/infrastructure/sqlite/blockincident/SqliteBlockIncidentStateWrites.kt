package skillbill.infrastructure.sqlite.blockincident

import skillbill.infrastructure.sqlite.core.ops.bindAll
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
      statement.bindAll(listOf(recovery.wireValue, occurrenceToken, expectedRevision))
      statement.executeUpdate() == 1
    }
}
