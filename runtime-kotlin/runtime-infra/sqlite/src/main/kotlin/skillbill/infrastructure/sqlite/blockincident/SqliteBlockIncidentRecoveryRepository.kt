package skillbill.infrastructure.sqlite.blockincident

import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.blockincident.BlockIncidentRecoveryRepository
import skillbill.ports.blockincident.model.BlockIncidentRecoveryTransition
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAction
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryFailure
import java.sql.Connection
import java.time.Instant

internal class SqliteBlockIncidentRecoveryRepository(
  private val connection: Connection,
) : BlockIncidentRecoveryRepository {
  override fun insertIfAbsent(attempt: BlockIncidentRecoveryAttempt): BlockIncidentRecoveryAttempt {
    connection.prepareStatement(
      """
      INSERT INTO block_incident_recovery_attempts (
        attempt_id, occurrence_token, action, incident_revision, idempotency_key, status, failure,
        owner_token, owner_generation, owner_expires_at, requested_at, updated_at,
        target_workflow_id, target_invocation_id
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(idempotency_key) DO NOTHING
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        listOf(
          attempt.attemptId, attempt.occurrenceToken, attempt.action.wireValue, attempt.incidentRevision,
          attempt.idempotencyKey, attempt.status.wireValue, attempt.failure?.wireValue,
          attempt.ownerToken, attempt.ownerGeneration, attempt.ownerExpiresAt?.toString(),
          attempt.requestedAt.toString(),
          attempt.updatedAt.toString(),
          attempt.targetWorkflowId,
          attempt.targetInvocationId,
        ),
      )
      statement.executeUpdate()
    }
    return findOne("idempotency_key = ?", attempt.idempotencyKey) ?: error("Inserted recovery attempt disappeared.")
  }

  override fun transition(change: BlockIncidentRecoveryTransition): Boolean {
    check(change.expectedStatus.canMoveTo(change.newStatus))
    val ownedClause =
      if (change.expectedStatus in BlockIncidentRecoveryAttemptStatus.OWNED) {
        "AND owner_token = ?"
      } else {
        ""
      }
    return connection.prepareStatement(
      "UPDATE block_incident_recovery_attempts SET status = ?, failure = ?, owner_token = ?, " +
        "owner_generation = ?, owner_expires_at = ?, updated_at = ? WHERE attempt_id = ? AND status = ? $ownedClause",
    ).use { statement ->
      val values =
        listOf(
          change.newStatus.wireValue,
          change.failure?.wireValue,
          change.ownerToken.takeIf { change.ownerExpiresAt != null },
          change.ownerGeneration.takeIf { change.ownerExpiresAt != null },
          change.ownerExpiresAt?.toString(),
          change.now.toString(),
          change.attemptId,
          change.expectedStatus.wireValue,
        )
      statement.bindAll(if (ownedClause.isNotEmpty()) values + change.ownerToken else values)
      statement.executeUpdate() == 1
    }
  }

  override fun inProgress(token: String): BlockIncidentRecoveryAttempt? =
    query(
      "occurrence_token = ? AND status IN (?, ?, ?)",
      listOf(token) + BlockIncidentRecoveryAttemptStatus.IN_PROGRESS.map { it.wireValue },
      1,
      "requested_at DESC",
    ).firstOrNull()

  override fun byIdempotencyKey(key: String): BlockIncidentRecoveryAttempt? = findOne("idempotency_key = ?", key)

  override fun latest(token: String): BlockIncidentRecoveryAttempt? = findOne("occurrence_token = ?", token)

  override fun history(token: String): List<BlockIncidentRecoveryAttempt> =
    query("occurrence_token = ?", listOf(token), HISTORY_LIMIT, "requested_at DESC")

  override fun interrupted(
    now: Instant,
    limit: Int,
  ): List<BlockIncidentRecoveryAttempt> {
    require(limit in 1..MAX_INTERRUPTED)
    return query(
      "status IN (?, ?) AND owner_expires_at < ?",
      listOf(
        BlockIncidentRecoveryAttemptStatus.APPLYING.wireValue,
        BlockIncidentRecoveryAttemptStatus.RESUMING.wireValue,
        now.toString(),
      ),
      limit,
      "updated_at ASC",
    )
  }

  private fun findOne(
    where: String,
    vararg values: String,
  ): BlockIncidentRecoveryAttempt? = query(where, values.toList(), 1, "requested_at DESC").firstOrNull()

  private fun query(
    where: String,
    values: List<String>,
    limit: Int,
    orderBy: String,
  ): List<BlockIncidentRecoveryAttempt> =
    connection.prepareStatement(
      "SELECT * FROM block_incident_recovery_attempts WHERE $where ORDER BY $orderBy LIMIT ?",
    ).use { statement ->
      values.forEachIndexed { index, value -> statement.setString(index + 1, value) }
      statement.setInt(values.size + 1, limit)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) {
            add(
              BlockIncidentRecoveryAttempt(
                rows.getString("attempt_id"), rows.getString("occurrence_token"),
                BlockIncidentRecoveryAction.entries.decode(rows.getString("action")) { it.wireValue },
                rows.getLong("incident_revision"), rows.getString("idempotency_key"),
                BlockIncidentRecoveryAttemptStatus.entries.decode(rows.getString("status")) { it.wireValue },
                rows.getString("failure")?.let {
                    value ->
                  BlockIncidentRecoveryFailure.entries.decode(value) { it.wireValue }
                },
                rows.getString("owner_token"), rows.getLong("owner_generation").takeUnless { rows.wasNull() },
                rows.getString("owner_expires_at")?.let(Instant::parse), Instant.parse(rows.getString("requested_at")),
                Instant.parse(rows.getString("updated_at")), rows.getString("target_workflow_id"),
                rows.getString("target_invocation_id"),
              ),
            )
          }
        }
      }
    }

  private fun <T> List<T>.decode(
    value: String,
    wire: (T) -> String,
  ): T = firstOrNull { wire(it) == value } ?: throw incidentCorrupt("Unsupported block incident recovery value.")

  private companion object {
    const val HISTORY_LIMIT = 64
    const val MAX_INTERRUPTED = 100
  }
}
