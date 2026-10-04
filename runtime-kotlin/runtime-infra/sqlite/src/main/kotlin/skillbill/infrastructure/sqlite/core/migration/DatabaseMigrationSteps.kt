package skillbill.infrastructure.sqlite.core.migration

import skillbill.infrastructure.sqlite.core.schema.DatabaseReviewColumnMigrations
import skillbill.infrastructure.sqlite.core.schema.DatabaseReviewFindingColumnMigrations
import skillbill.infrastructure.sqlite.core.schema.DatabaseReviewLedgerSchema
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.sql.Connection

internal fun persistGoalPlanningRepairEvidence(connection: Connection) {
  connection.createStatement().use { statement ->
    listOf("goal_shared_preplans", "goal_subtask_plans").forEach { table ->
      val hasEvidenceColumn =
        statement.executeQuery(
          "SELECT 1 FROM pragma_table_info('$table') WHERE name = 'repair_evidence_json'",
        ).use { rows -> rows.next() }
      if (!hasEvidenceColumn) {
        statement.execute("ALTER TABLE $table ADD COLUMN repair_evidence_json TEXT")
      }
    }
  }
}

internal fun persistLegacyGoalPlanningRepairEvidence(connection: Connection) {
  connection.createStatement().use { statement ->
    listOf("preplan_repair_evidence_json", "plan_repair_evidence_json").forEach { column ->
      val hasEvidenceColumn =
        statement.executeQuery(
          "SELECT 1 FROM pragma_table_info('goal_planning_preparations') WHERE name = '$column'",
        ).use { rows -> rows.next() }
      if (!hasEvidenceColumn) {
        statement.execute("ALTER TABLE goal_planning_preparations ADD COLUMN $column TEXT")
      }
    }
  }
}

internal fun addGoalRunnerControls(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS goal_runner_controls (
        parent_workflow_id TEXT PRIMARY KEY,
        review_policy_json TEXT,
        out_of_band_acceptances_json TEXT NOT NULL DEFAULT '[]',
        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
      )
      """.trimIndent(),
    )
  }
}

internal fun addGoalRunnerControlState(connection: Connection) {
  connection.createStatement().use { statement ->
    val hasControlState =
      statement.executeQuery(
        "SELECT 1 FROM pragma_table_info('goal_runner_controls') WHERE name = 'control_state_json'",
      ).use { rows -> rows.next() }
    if (!hasControlState) {
      statement.execute("ALTER TABLE goal_runner_controls ADD COLUMN control_state_json TEXT")
    }
  }
}

internal fun addDelegatedReviewLifecycleProjection(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS review_delegated_lifecycle (
        review_id TEXT PRIMARY KEY,
        packet_digest TEXT NOT NULL,
        contract_version TEXT NOT NULL CHECK (contract_version = '0.1'),
        bounded_payload_json TEXT NOT NULL,
        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
      )
      """.trimIndent(),
    )
    statement.execute(
      """
      CREATE INDEX IF NOT EXISTS idx_review_delegated_lifecycle_review
        ON review_delegated_lifecycle(review_id, updated_at)
      """.trimIndent(),
    )
  }
}

internal fun addReviewRunLaneAttribution(connection: Connection) {
  DatabaseReviewLedgerSchema.reviewRunLaneStatements.forEach { sql ->
    connection.createStatement().use { statement -> statement.execute(sql) }
  }
  DatabaseReviewFindingColumnMigrations.ensureFindingLaneColumns(connection)
  DatabaseReviewColumnMigrations.ensureReviewRunLaneDispositionColumns(connection)
}

internal fun addReviewFindingOutcomeKey(connection: Connection) {
  DatabaseReviewColumnMigrations.ensureReviewFindingOutcomeKeyColumns(connection)
  DatabaseReviewLedgerSchema.reviewFindingOutcomeStatements.forEach { sql ->
    connection.createStatement().use { statement -> statement.execute(sql) }
  }
  DatabaseReviewColumnMigrations.ensureReviewFindingOutcomeColumns(connection)
}

internal fun rekeyProducerOutputEvidenceByAgent(connection: Connection) {
  if (producerOutputEvidencePrimaryKeyIncludesAgentId(connection)) return
  connection.createStatement().use {
    it.execute(
      "ALTER TABLE producer_output_evidence RENAME TO producer_output_evidence_pre_agent",
    )
    it.execute(
      """
      CREATE TABLE IF NOT EXISTS producer_output_evidence (
        workflow_id TEXT NOT NULL, phase_id TEXT NOT NULL,
        generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),
        attempt INTEGER NOT NULL CHECK (attempt > 0),
        agent_id TEXT NOT NULL, model TEXT NOT NULL, recorded_at TEXT NOT NULL,
        byte_size INTEGER NOT NULL CHECK (byte_size >= 0), sha256 TEXT NOT NULL, payload BLOB,
        PRIMARY KEY (workflow_id, phase_id, generation, attempt, agent_id)
      )
      """.trimIndent(),
    )
    it.execute(
      """
      INSERT INTO producer_output_evidence (
        workflow_id, phase_id, generation, attempt, agent_id, model, recorded_at,
        byte_size, sha256, payload
      )
      SELECT workflow_id, phase_id, generation, attempt, agent_id, model, recorded_at,
             byte_size, sha256, payload
      FROM producer_output_evidence_pre_agent
      """.trimIndent(),
    )
    it.execute("DROP TABLE producer_output_evidence_pre_agent")
  }
}

internal fun producerOutputEvidencePrimaryKeyIncludesAgentId(connection: Connection): Boolean {
  val ddl =
    connection.createStatement().use { statement ->
      statement.executeQuery(
        "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'producer_output_evidence'",
      ).use { rows ->
        if (!rows.next()) return false
        rows.getString("sql").orEmpty()
      }
    }
  return Regex(
    """PRIMARY\s+KEY\s*\([^)]*\bagent_id\b[^)]*\)""",
    RegexOption.IGNORE_CASE,
  ).containsMatchIn(ddl)
}

internal fun dropDelegatedReviewLifecycleTables(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute("DROP INDEX IF EXISTS idx_review_delegated_lifecycle_review")
    statement.execute("DROP INDEX IF EXISTS idx_review_lifecycle_events_review")
    statement.execute("DROP TABLE IF EXISTS review_delegated_lifecycle")
    statement.execute("DROP TABLE IF EXISTS review_lifecycle_events")
  }
}

internal fun ensureSchemaColumnsAndHeals(connection: Connection) {
  DatabaseColumnMigrations.apply(connection)
  DatabaseColumnMigrations.healDiagnosticEvidenceKeys(connection)
  DatabaseColumnMigrations.healWorkListMetadata(connection)
}

internal class DatabaseMigration(
  internal val version: Int,
  internal val name: String,
  private val operation: (Connection, RuntimeDiagnostics) -> Unit,
) {
  constructor(
    version: Int,
    name: String,
    operation: (Connection) -> Unit,
  ) : this(version, name, { connection, _ -> operation(connection) })

  fun apply(
    connection: Connection,
    diagnostics: RuntimeDiagnostics,
  ) {
    operation(connection, diagnostics)
  }
}
