package skillbill.infrastructure.sqlite

import skillbill.contracts.SharedPayloadKeys
import skillbill.error.core.RejectedOutputDiagnosticFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rejectedOutputDiagnosticConflictMessage
import skillbill.error.core.rejectedOutputDiagnosticCorruptMessage
import skillbill.error.core.rejectedOutputDiagnosticPersistenceMessage
import skillbill.error.shellcontent.InvalidProducerOutputEvidenceSchemaError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.diagnostics.RejectedOutputDiagnosticRepository
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticInsert
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticRead
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticRecord
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticSelector
import skillbill.ports.diagnostics.model.RejectedOutputLifecycle
import skillbill.ports.diagnostics.model.evidenceKey
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.format.DateTimeParseException

internal class SqliteRejectedOutputDiagnosticRepository(
  private val connection: Connection,
) : RejectedOutputDiagnosticRepository {
  override fun insert(record: RejectedOutputDiagnosticRecord): RejectedOutputDiagnosticInsert {
    val existing = persistence("insert-read-existing") { find(record.metadata.identity) }
    if (existing != null) {
      if (!existing.sameImmutableEvidence(record)) {
        return RejectedOutputDiagnosticInsert.Conflict(record.metadata.identity)
      }
      return RejectedOutputDiagnosticInsert.Inserted(existing)
    }
    try {
      connection.prepareStatement(
        """
        INSERT INTO rejected_output_diagnostics (
          identity, workflow_id, phase_id, attempt, repair_turn, rule, rejection_path, reason, agent_id, model,
          recorded_at, byte_size, sha256, lifecycle, payload
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { statement ->
        val metadata = record.metadata
        statement.bindAll(
          metadata.identity,
          metadata.workflowId,
          metadata.phaseId,
          metadata.attempt,
          metadata.repairTurn,
          metadata.rule,
          metadata.path,
          metadata.reason,
          metadata.agentId,
          metadata.model,
          metadata.recordedAt.toString(),
          metadata.byteSize,
          metadata.sha256,
          metadata.lifecycle.name.lowercase(),
          record.payload,
        )
        statement.executeUpdate()
      }
      return RejectedOutputDiagnosticInsert.Inserted(record)
    } catch (error: SQLException) {
      val raced = persistence("insert-read-raced") { find(record.metadata.identity) }
      if (raced != null && raced.sameImmutableEvidence(record)) return RejectedOutputDiagnosticInsert.Inserted(raced)
      throw SkillBillRuntimeException(
        RejectedOutputDiagnosticFailureCode.PERSISTENCE,
        rejectedOutputDiagnosticPersistenceMessage("insert"),
        error,
      )
    }
  }

  override fun select(selector: RejectedOutputDiagnosticSelector): List<RejectedOutputDiagnostic> {
    return persistence("select") {
      connection.prepareStatement(
        "${selectColumns()} WHERE ${selector.whereClause()} ORDER BY phase_id, attempt, repair_turn",
      ).use { statement ->
        selector.bindAll(statement)
        statement.executeQuery().use { rows ->
          buildList { while (rows.next()) add(rows.toRecord().metadata) }
        }
      }
    }
  }

  override fun read(identity: String): RejectedOutputDiagnosticRead =
    persistence("read") {
      find(identity)?.classified() ?: RejectedOutputDiagnosticRead.Absent(identity)
    }

  override fun markExpired(before: Instant): Int =
    persistence("mark-expired") {
      connection.prepareStatement(
        """
        UPDATE rejected_output_diagnostics
        SET lifecycle = 'expired', payload = NULL
        WHERE lifecycle = 'stored' AND recorded_at < ?
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(before.toString())
        statement.executeUpdate()
      }
    }

  override fun delete(selector: RejectedOutputDiagnosticSelector): Int =
    persistence("delete") {
      connection.prepareStatement(
        "DELETE FROM rejected_output_diagnostics WHERE ${selector.whereClause()}",
      ).use { statement ->
        selector.bindAll(statement)
        statement.executeUpdate()
      }
    }

  override fun retainProducerOutput(evidence: ProducerOutputEvidence) {
    persistence("retain-producer-output") {
      connection.prepareStatement(
        """
        INSERT OR IGNORE INTO producer_output_evidence
        (workflow_id, phase_id, generation, attempt, repair_turn, agent_id, model, recorded_at,
         byte_size, sha256, payload)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use {
        it.bindAll(
          evidence.workflowId,
          evidence.phaseId,
          evidence.generation,
          evidence.attempt,
          evidence.repairTurn,
          evidence.agentId,
          evidence.model,
          evidence.recordedAt.toString(),
          evidence.byteSize,
          evidence.sha256,
          evidence.payload,
        )
        it.executeUpdate()
      }
      val retained =
        connection.queryProducerEvidence(
          ProducerEvidenceLookup(
            workflowId = evidence.workflowId,
            phaseId = evidence.phaseId,
            attempt = evidence.attempt,
            agentId = evidence.agentId,
            generation = evidence.generation,
            exactGeneration = true,
            repairTurn = evidence.repairTurn,
          ),
        ) ?: throw SkillBillRuntimeException(
          RejectedOutputDiagnosticFailureCode.PERSISTENCE,
          rejectedOutputDiagnosticPersistenceMessage("retain-producer-output-readback"),
        )
      if (retained.sha256 != evidence.sha256 || retained.byteSize != evidence.byteSize ||
        !payloadsEqual(retained.payload, evidence.payload)
      ) {
        throw SkillBillRuntimeException(
          RejectedOutputDiagnosticFailureCode.CONFLICT,
          rejectedOutputDiagnosticConflictMessage(evidence.evidenceKey()),
        )
      }
    }
  }

  override fun readProducerOutput(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    agentId: String,
    generation: Int,
  ): ProducerOutputEvidence? =
    persistence("read-producer-output") {
      connection.queryProducerEvidence(
        ProducerEvidenceLookup(
          workflowId = workflowId,
          phaseId = phaseId,
          attempt = attempt,
          agentId = agentId,
          generation = generation,
          exactGeneration = false,
          repairTurn = null,
        ),
      )
    }

  override fun deleteProducerOutputsBefore(before: Instant): Int =
    persistence("delete-producer-outputs") {
      connection.prepareStatement("DELETE FROM producer_output_evidence WHERE recorded_at < ?").use {
        it.bindAll(before.toString())
        it.executeUpdate()
      }
    }

  private fun find(identity: String): RejectedOutputDiagnosticRecord? =
    connection.prepareStatement(
      "${selectColumns()} WHERE identity = ?",
    ).use { statement ->
      statement.bindAll(identity)
      statement.executeQuery().use { rows -> if (rows.next()) rows.toRecord() else null }
    }

  private fun selectColumns(): String =
    """
    SELECT identity, workflow_id, phase_id, attempt, repair_turn, rule, rejection_path, reason, agent_id, model,
           recorded_at, byte_size, sha256, lifecycle, payload
    FROM rejected_output_diagnostics
    """.trimIndent()
}

private fun RejectedOutputDiagnosticSelector.whereClause(): String =
  buildList {
    add("workflow_id = ?")
    if (phaseId != null) add("phase_id = ?")
    if (attempt != null) add("attempt = ?")
    if (repairTurn != null) add("repair_turn = ?")
  }.joinToString(" AND ")

private fun RejectedOutputDiagnosticSelector.bindAll(statement: PreparedStatement) {
  val values =
    buildList<Any?> {
      add(workflowId)
      phaseId?.let(::add)
      attempt?.let(::add)
      repairTurn?.let(::add)
    }
  statement.bindAll(values)
}

private inline fun <T> persistence(
  operation: String,
  block: () -> T,
): T =
  try {
    block()
  } catch (error: SQLException) {
    throw SkillBillRuntimeException(
      RejectedOutputDiagnosticFailureCode.PERSISTENCE,
      rejectedOutputDiagnosticPersistenceMessage(operation),
      error,
    )
  }

private fun RejectedOutputDiagnosticRecord.classified(): RejectedOutputDiagnosticRead =
  when (metadata.lifecycle) {
    RejectedOutputLifecycle.STORED -> RejectedOutputDiagnosticRead.Found(this)
    RejectedOutputLifecycle.EXPIRED -> RejectedOutputDiagnosticRead.Expired(this)
    RejectedOutputLifecycle.OVERSIZED -> RejectedOutputDiagnosticRead.Oversized(this)
  }

private fun ResultSet.toRecord(): RejectedOutputDiagnosticRecord {
  val identity =
    try {
      getString("identity")
    } catch (error: SQLException) {
      corruptRecord("<unreadable>", error)
    }
  return try {
    RejectedOutputDiagnosticRecord(
      metadata =
        RejectedOutputDiagnostic(
          identity = identity,
          workflowId = getString(SharedPayloadKeys.WORKFLOW_ID),
          phaseId = getString(SharedPayloadKeys.PHASE_ID),
          attempt = getInt("attempt"),
          rule = getString("rule"),
          path = getString("rejection_path"),
          reason = getString("reason"),
          agentId = getString("agent_id"),
          model = getString("model"),
          recordedAt = Instant.parse(getString("recorded_at")),
          byteSize = getLong("byte_size"),
          sha256 = getString("sha256"),
          lifecycle = RejectedOutputLifecycle.valueOf(getString("lifecycle").uppercase()),
          repairTurn = getInt("repair_turn"),
        ),
      payload = getBytes("payload"),
    )
  } catch (error: SQLException) {
    corruptRecord(identity, error)
  } catch (error: DateTimeParseException) {
    corruptRecord(identity, error)
  } catch (error: IllegalArgumentException) {
    corruptRecord(identity, error)
  }
}

private fun corruptRecord(
  identity: String,
  error: Throwable,
): Nothing =
  throw SkillBillRuntimeException(
    RejectedOutputDiagnosticFailureCode.CORRUPT,
    rejectedOutputDiagnosticCorruptMessage(identity),
    error,
  )

private fun RejectedOutputDiagnosticRecord.sameImmutableEvidence(other: RejectedOutputDiagnosticRecord): Boolean =
  metadata.copy(recordedAt = other.metadata.recordedAt) == other.metadata &&
    (
      (payload == null && other.payload == null) || (
        payload != null && other.payload != null &&
          payload.contentEquals(
            other.payload,
          )
      )
    )

private fun payloadsEqual(
  left: ByteArray?,
  right: ByteArray?,
): Boolean = (left == null && right == null) || (left != null && right != null && left.contentEquals(right))

private data class ProducerEvidenceLookup(
  internal val workflowId: String,
  internal val phaseId: String,
  internal val attempt: Int,
  internal val agentId: String,
  internal val generation: Int,
  internal val exactGeneration: Boolean,
  internal val repairTurn: Int?,
)

private fun Connection.queryProducerEvidence(lookup: ProducerEvidenceLookup): ProducerOutputEvidence? {
  val generationPredicate = if (lookup.exactGeneration) "generation = ?" else "generation <= ?"
  val repairTurnPredicate = if (lookup.repairTurn == null) "" else " AND repair_turn = ?"
  return prepareStatement(
    """
    SELECT * FROM producer_output_evidence
    WHERE workflow_id = ? AND phase_id = ? AND attempt = ? AND agent_id = ?
      AND $generationPredicate$repairTurnPredicate
    ORDER BY generation DESC, repair_turn DESC LIMIT 1
    """.trimIndent(),
  ).use {
    val values =
      buildList<Any?> {
        add(lookup.workflowId)
        add(lookup.phaseId)
        add(lookup.attempt)
        add(lookup.agentId)
        add(lookup.generation)
        lookup.repairTurn?.let(::add)
      }
    it.bindAll(values)
    it.executeQuery().use { row -> if (row.next()) row.toProducerEvidence() else null }
  }
}

private fun ResultSet.toProducerEvidence(): ProducerOutputEvidence {
  val workflowId = getString(SharedPayloadKeys.WORKFLOW_ID)
  val phaseId = getString(SharedPayloadKeys.PHASE_ID)
  val attempt = getInt("attempt")
  val generation = getInt("generation")
  if (getObject("generation") == null || generation < 0) {
    throw InvalidProducerOutputEvidenceSchemaError(
      "Producer output evidence '$workflowId:$phaseId:$attempt' carries an unusable generation.",
    )
  }
  return ProducerOutputEvidence(
    workflowId = workflowId,
    phaseId = phaseId,
    attempt = attempt,
    agentId = getString("agent_id"),
    model = getString("model"),
    recordedAt = Instant.parse(getString("recorded_at")),
    byteSize = getLong("byte_size"),
    sha256 = getString("sha256"),
    payload = getBytes("payload"),
    generation = generation,
    repairTurn = getInt("repair_turn"),
  )
}
