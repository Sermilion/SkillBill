package skillbill.infrastructure.sqlite

import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementKind
import java.sql.Connection

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
