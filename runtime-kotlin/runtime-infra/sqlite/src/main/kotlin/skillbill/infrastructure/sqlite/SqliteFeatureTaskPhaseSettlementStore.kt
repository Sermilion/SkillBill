package skillbill.infrastructure.sqlite

import skillbill.contracts.SharedPayloadKeys
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementKind
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementReasonLookup
import java.sql.Connection
import java.time.Instant

internal class SqliteFeatureTaskPhaseSettlementStore(
  private val connection: Connection,
) : FeatureTaskPhaseSettlementRepository {
  override fun upsert(settlement: FeatureTaskPhaseSettlement) {
    connection.prepareStatement(
      """
      INSERT INTO feature_task_phase_settlements (
        workflow_id, phase_id, attempt, kind, envelope_json, recorded_at
      ) VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(workflow_id, phase_id, attempt) DO UPDATE SET
        kind = excluded.kind,
        envelope_json = excluded.envelope_json,
        recorded_at = excluded.recorded_at
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        settlement.workflowId,
        settlement.phaseId,
        settlement.attempt,
        settlement.kind.wireValue,
        settlement.envelopeJson,
        settlement.recordedAt,
      )
      statement.executeUpdate()
    }
  }

  override fun find(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): FeatureTaskPhaseSettlement? {
    connection.prepareStatement(
      """
      SELECT workflow_id, phase_id, attempt, kind, envelope_json, recorded_at
      FROM feature_task_phase_settlements
      WHERE workflow_id = ? AND phase_id = ? AND attempt = ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(workflowId, phaseId, attempt)
      statement.executeQuery().use { rows ->
        if (!rows.next()) return null
        return FeatureTaskPhaseSettlement(
          workflowId = rows.getString("workflow_id"),
          phaseId = rows.getString("phase_id"),
          attempt = rows.getInt("attempt"),
          kind = FeatureTaskPhaseSettlementKind.fromWire(rows.getString("kind")),
          envelopeJson = rows.getString("envelope_json"),
          recordedAt = rows.getString("recorded_at"),
        )
      }
    }
  }

  override fun history(
    workflowId: String,
    phaseId: String,
  ): List<FeatureTaskPhaseSettlement> =
    connection.prepareStatement(
      "SELECT workflow_id, phase_id, attempt, kind, envelope_json, recorded_at " +
        "FROM feature_task_phase_settlements WHERE workflow_id = ? AND phase_id = ? ORDER BY attempt DESC",
    ).use { statement ->
      statement.bindAll(workflowId, phaseId)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) add(
            FeatureTaskPhaseSettlement(
              rows.getString("workflow_id"),
              rows.getString("phase_id"),
              rows.getInt("attempt"),
              FeatureTaskPhaseSettlementKind.fromWire(rows.getString("kind")),
              rows.getString("envelope_json"),
              rows.getString("recorded_at"),
            ),
          )
        }
      }
    }

  override fun findReasonAtOrBefore(
    workflowId: String,
    phaseId: String,
    startedAt: Instant,
    recordedAt: Instant,
    storedReason: String,
  ): FeatureTaskPhaseSettlementReasonLookup =
    connection.prepareStatement(
      """
      WITH candidates AS (
        SELECT *,
          CASE WHEN json_valid(envelope_json) THEN json_extract(envelope_json, '$.${SharedPayloadKeys.SUMMARY}') END AS summary,
          CASE WHEN json_valid(envelope_json) THEN json_extract(envelope_json, '$.${SharedPayloadKeys.PRODUCED_OUTPUTS}.${SharedPayloadKeys.VALUE}') END AS full_reason
        FROM feature_task_phase_settlements
        WHERE workflow_id = ? AND phase_id = ?
          AND ($SECONDS > ? OR ($SECONDS = ? AND $NANOS >= ?))
          AND ($SECONDS < ? OR ($SECONDS = ? AND $NANOS <= ?))
        ORDER BY $SECONDS DESC, $NANOS DESC, attempt DESC, kind DESC
        LIMIT $REASON_CANDIDATE_SENTINEL
      ), matches AS (
        SELECT workflow_id, phase_id, attempt, kind, envelope_json, recorded_at,
          summary, full_reason
        FROM candidates
        WHERE json_valid(envelope_json) = 0 OR (
          typeof(summary) = 'text' AND typeof(full_reason) = 'text'
          AND substr(?, -length(summary)) = summary
          AND instr(substr(?, 1, length(?) - length(summary)) || full_reason, ?) = 1
        )
      ), counts AS (
        SELECT (SELECT COUNT(*) FROM candidates) AS candidate_count, COUNT(*) AS match_count FROM matches
      )
      SELECT matches.workflow_id, matches.phase_id, matches.attempt, matches.kind, matches.envelope_json, matches.recorded_at,
        counts.candidate_count, counts.match_count
      FROM counts LEFT JOIN matches ON 1 = 1
      """.trimIndent(),
    ).use { statement ->
      val second = recordedAt.toString().take(INSTANT_SECOND_TEXT_LENGTH)
      statement.bindAll(
        listOf(
          workflowId, phaseId,
          startedAt.toString().take(INSTANT_SECOND_TEXT_LENGTH),
          startedAt.toString().take(INSTANT_SECOND_TEXT_LENGTH), startedAt.nano,
          second, second, recordedAt.nano,
          storedReason, storedReason, storedReason, storedReason.removeSuffix("..."),
        ),
      )
      statement.executeQuery().use { rows ->
        check(rows.next())
        val candidateCount = rows.getInt("candidate_count")
        val matchCount = rows.getInt("match_count")
        when {
          candidateCount > REASON_CANDIDATE_LIMIT -> FeatureTaskPhaseSettlementReasonLookup.BudgetExceeded
          matchCount == 0 -> FeatureTaskPhaseSettlementReasonLookup.Missing
          matchCount > 1 -> FeatureTaskPhaseSettlementReasonLookup.Ambiguous
          else ->
            FeatureTaskPhaseSettlementReasonLookup.Unique(
              FeatureTaskPhaseSettlement(
                rows.getString("workflow_id"),
                rows.getString("phase_id"),
                rows.getInt("attempt"),
                FeatureTaskPhaseSettlementKind.fromWire(rows.getString("kind")),
                rows.getString("envelope_json"),
                rows.getString("recorded_at"),
              ),
            )
        }
      }
    }

  private companion object {
    const val SECONDS = "substr(recorded_at, 1, 19)"
    const val NANOS =
      "CASE WHEN substr(recorded_at, 20, 1) = '.' THEN " +
        "CAST(substr(substr(recorded_at, 21, instr(substr(recorded_at, 21), 'Z') - 1) || " +
        "'000000000', 1, 9) AS INTEGER) ELSE 0 END"
  }

  override fun delete(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): Boolean {
    connection.prepareStatement(
      """
      DELETE FROM feature_task_phase_settlements
      WHERE workflow_id = ? AND phase_id = ? AND attempt = ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(workflowId, phaseId, attempt)
      return statement.executeUpdate() > 0
    }
  }
}

private const val INSTANT_SECOND_TEXT_LENGTH = 19
private const val REASON_CANDIDATE_LIMIT = 64
private const val REASON_CANDIDATE_SENTINEL = REASON_CANDIDATE_LIMIT + 1
