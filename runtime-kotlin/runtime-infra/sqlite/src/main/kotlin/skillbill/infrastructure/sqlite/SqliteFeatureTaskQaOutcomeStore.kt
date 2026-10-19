package skillbill.infrastructure.sqlite

import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.featuretask.FeatureTaskQaOutcomeRepository
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import java.sql.Connection

internal class SqliteFeatureTaskQaOutcomeStore(
  private val connection: Connection,
) : FeatureTaskQaOutcomeRepository {
  override fun upsert(outcome: FeatureTaskQaOutcome) {
    connection.prepareStatement(
      """
      INSERT INTO feature_task_qa_outcomes (
        workflow_id, outcome, reason, qa_fix_attempts, tested_tree_identity, recorded_at
      ) VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(workflow_id) DO UPDATE SET
        outcome = excluded.outcome,
        reason = excluded.reason,
        qa_fix_attempts = excluded.qa_fix_attempts,
        tested_tree_identity = excluded.tested_tree_identity,
        recorded_at = excluded.recorded_at
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        outcome.workflowId,
        outcome.outcome.wireValue,
        outcome.reason,
        outcome.qaFixAttempts,
        outcome.testedTreeIdentity,
        outcome.recordedAt,
      )
      statement.executeUpdate()
    }
  }

  override fun find(workflowId: String): FeatureTaskQaOutcome? =
    connection.prepareStatement(
      """
      SELECT workflow_id, outcome, reason, qa_fix_attempts, tested_tree_identity, recorded_at
      FROM feature_task_qa_outcomes
      WHERE workflow_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(workflowId)
      statement.executeQuery().use { rows ->
        if (!rows.next()) return@use null
        val raw = rows.getString("outcome")
        FeatureTaskQaOutcome(
          workflowId = rows.getString("workflow_id"),
          outcome =
            StandaloneQaOutcome.fromWire(raw)
              ?: throw invalidWorkflowStateSchemaError("Stored QA outcome '$raw' is not a known outcome."),
          reason = rows.getString("reason"),
          qaFixAttempts = rows.getInt("qa_fix_attempts"),
          testedTreeIdentity = rows.getString("tested_tree_identity"),
          recordedAt = rows.getString("recorded_at"),
        )
      }
    }
}
