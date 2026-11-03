package skillbill.infrastructure.sqlite

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.sqlite.core.migration.area.rebuildGoalPlanningPlansForPhaseOutputVersion6
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoalPlanningPhaseOutputMigrationTest {
  @Test
  fun `forward migration preserves compatible goal planning rows and tightens both tables`() {
    val dbPath = Files.createTempDirectory("goal-planning-compatible-migration").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      revertRepairEvidenceMigrations(connection)
      seedPlanningRow(connection, phaseOutputContractVersion = "0.2")
      seedLegacyPlanningRow(connection)

      DatabaseMigrations.apply(connection)

      assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM goal_shared_preplans"))
      assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM goal_subtask_plans"))
      assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM goal_planning_preparations"))
      assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM schema_migrations WHERE version = 17"))
      assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM schema_migrations WHERE version = 18"))
      assertTrue(tableHasColumn(connection, "goal_shared_preplans", "repair_evidence_json"))
      assertTrue(tableHasColumn(connection, "goal_subtask_plans", "repair_evidence_json"))
      assertTrue(tableHasColumn(connection, "goal_planning_preparations", "preplan_repair_evidence_json"))
      assertTrue(tableHasColumn(connection, "goal_planning_preparations", "plan_repair_evidence_json"))
      connection.createStatement().use { statement ->
        statement.execute("UPDATE goal_shared_preplans SET repair_evidence_json = 'shared-repair-evidence'")
        statement.execute("UPDATE goal_subtask_plans SET repair_evidence_json = 'subtask-repair-evidence'")
        statement.execute(
          "UPDATE goal_planning_preparations SET " +
            "preplan_repair_evidence_json = 'legacy-preplan-repair-evidence', " +
            "plan_repair_evidence_json = 'legacy-plan-repair-evidence'",
        )
      }
      assertEquals(
        "shared-repair-evidence",
        textScalar(connection, "SELECT repair_evidence_json FROM goal_shared_preplans"),
      )
      assertEquals(
        "subtask-repair-evidence",
        textScalar(connection, "SELECT repair_evidence_json FROM goal_subtask_plans"),
      )
      assertEquals(
        "legacy-preplan-repair-evidence",
        textScalar(connection, "SELECT preplan_repair_evidence_json FROM goal_planning_preparations"),
      )
      assertEquals(
        "legacy-plan-repair-evidence",
        textScalar(connection, "SELECT plan_repair_evidence_json FROM goal_planning_preparations"),
      )
      assertFailsWith<SQLException> {
        seedPlanningRow(connection, phaseOutputContractVersion = "0.1", workflowId = "wfl-incompatible")
      }
    }
  }

  @Test
  fun `forward migration rejects incompatible provenance before changing schema or rows`() {
    val dbPath = Files.createTempDirectory("goal-planning-incompatible-migration").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      revertRepairEvidenceMigrations(connection)
      connection.createStatement().use { it.execute("PRAGMA ignore_check_constraints = ON") }
      seedPlanningRow(connection, phaseOutputContractVersion = "0.1")
      connection.createStatement().use { it.execute("PRAGMA ignore_check_constraints = OFF") }
      connection.createStatement().use { it.execute("DELETE FROM schema_migrations WHERE version = 11") }
      val sharedSchema = tableSql(connection, "goal_shared_preplans")
      val subtaskSchema = tableSql(connection, "goal_subtask_plans")

      assertFailsWith<SkillBillRuntimeException> {
        DatabaseMigrations.apply(connection)
      }

      assertEquals(sharedSchema, tableSql(connection, "goal_shared_preplans"))
      assertEquals(subtaskSchema, tableSql(connection, "goal_subtask_plans"))
      assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM goal_shared_preplans"))
      assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM goal_subtask_plans"))
      assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM schema_migrations WHERE version = 11"))
      assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM schema_migrations WHERE version = 17"))
      assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM schema_migrations WHERE version = 18"))
    }
  }

  @Test
  fun `phase-output 0-7 migration keeps stored 0-6 rows and admits the current contract version`() {
    val dbPath = Files.createTempDirectory("goal-planning-phase-output-0-7-migration").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      rebuildGoalPlanningPlansForPhaseOutputVersion6(connection)
      connection.createStatement().use { it.execute("DELETE FROM schema_migrations WHERE version = 46") }
      seedPlanningRow(connection, phaseOutputContractVersion = "0.6")
      assertFailsWith<SQLException> {
        seedPlanningRow(
          connection,
          phaseOutputContractVersion = FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
          workflowId = "wfl-before-migration",
        )
      }

      DatabaseMigrations.apply(connection)

      assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM schema_migrations WHERE version = 46"))
      assertEquals(
        "0.6",
        textScalar(connection, "SELECT phase_output_contract_version FROM goal_shared_preplans"),
      )
      assertEquals(
        "0.6",
        textScalar(connection, "SELECT phase_output_contract_version FROM goal_subtask_plans"),
      )
      seedPlanningRow(
        connection,
        phaseOutputContractVersion = FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        workflowId = "wfl-current",
      )
      assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM goal_shared_preplans"))
      assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM goal_subtask_plans"))
    }
  }

  private fun revertRepairEvidenceMigrations(connection: Connection) {
    revertDesktopChangeSignals(connection)
    connection.createStatement().use { statement ->
      statement.execute("ALTER TABLE goal_shared_preplans DROP COLUMN repair_evidence_json")
      statement.execute("ALTER TABLE goal_subtask_plans DROP COLUMN repair_evidence_json")
      statement.execute("ALTER TABLE goal_planning_preparations DROP COLUMN preplan_repair_evidence_json")
      statement.execute("ALTER TABLE goal_planning_preparations DROP COLUMN plan_repair_evidence_json")
      statement.execute("DELETE FROM schema_migrations WHERE version IN (17, 18)")
    }
  }

  private fun seedLegacyPlanningRow(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        INSERT INTO goal_planning_preparations (
          parent_goal_workflow_id, normalized_issue_key, repository_identity, subtask_id,
          governed_sub_spec_path, preparation_status, contract_version, parent_spec_hash,
          sub_spec_hash, decomposition_manifest_hash, phase_output_contract_id,
          phase_output_contract_version, preplan_payload_json, plan_payload_json
        ) VALUES (
          'wfl-planning', 'SKILL-000-wfl-planning', 'repo', 1, '.feature-specs/x/spec_subtask_1.md',
          'prepared', '0.1', 'spec-hash', 'sub-hash', 'manifest-hash',
          'feature-task-runtime-phase-output', '0.2', '{}', '{}'
        )
        """.trimIndent(),
      )
    }
  }

  @Test
  fun `migrated databases store multiple shared preplans for one issue key`() {
    val dbPath = Files.createTempDirectory("goal-shared-preplans-reuse-keys").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      DatabaseMigrations.apply(connection)
      restoreIssueKeyUnique(connection)
      seedPlanningRow(
        connection,
        phaseOutputContractVersion = "0.7",
        workflowId = "wfl-first",
        issueKey = "SKILL-472",
      )
      assertTrue("UNIQUE(normalized_issue_key, repository_identity)" in tableSql(connection, "goal_shared_preplans"))
      assertFailsWith<SQLException> {
        seedPlanningRow(
          connection,
          phaseOutputContractVersion = "0.7",
          workflowId = "wfl-second",
          issueKey = "SKILL-472",
        )
      }

      DatabaseMigrations.apply(connection)

      val sharedSchema = tableSql(connection, "goal_shared_preplans")
      assertTrue("UNIQUE(normalized_issue_key, repository_identity)" !in sharedSchema)
      seedPlanningRow(
        connection,
        phaseOutputContractVersion = "0.7",
        workflowId = "wfl-second",
        issueKey = "SKILL-472",
      )
      assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM goal_shared_preplans"))
      assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM schema_migrations WHERE version = 100"))
    }
  }

  private fun restoreIssueKeyUnique(connection: Connection) {
    val sharedDdl =
      issueKeyUniqueSharedPreplansDdl(optionalRepairEvidenceColumn(connection, "goal_shared_preplans"))
    val subtaskDdl =
      issueKeyUniqueSubtaskPlansDdl(optionalRepairEvidenceColumn(connection, "goal_subtask_plans"))
    connection.createStatement().use { statement ->
      statement.execute("ALTER TABLE goal_subtask_plans RENAME TO goal_subtask_plans_with_issue_key_unique")
      statement.execute("ALTER TABLE goal_shared_preplans RENAME TO goal_shared_preplans_with_issue_key_unique")
      statement.execute(sharedDdl)
      statement.execute(subtaskDdl)
      statement.execute(
        "INSERT INTO goal_shared_preplans SELECT * FROM goal_shared_preplans_with_issue_key_unique",
      )
      statement.execute(
        "INSERT INTO goal_subtask_plans SELECT * FROM goal_subtask_plans_with_issue_key_unique",
      )
      statement.execute("DROP TABLE goal_subtask_plans_with_issue_key_unique")
      statement.execute("DROP TABLE goal_shared_preplans_with_issue_key_unique")
      statement.execute("DELETE FROM schema_migrations WHERE version = 100")
    }
  }

  private fun optionalRepairEvidenceColumn(
    connection: Connection,
    table: String,
  ): String =
    if (tableHasColumn(connection, table, "repair_evidence_json")) {
      ",\n      repair_evidence_json TEXT"
    } else {
      ""
    }

  private fun issueKeyUniqueSharedPreplansDdl(repairEvidence: String): String =
    """
    CREATE TABLE goal_shared_preplans (
      parent_goal_workflow_id TEXT PRIMARY KEY,
      normalized_issue_key TEXT NOT NULL,
      repository_identity TEXT NOT NULL,
      preparation_status TEXT NOT NULL CHECK (preparation_status = 'prepared'),
      contract_version TEXT NOT NULL CHECK (contract_version = '0.2'),
      parent_spec_hash TEXT NOT NULL,
      decomposition_manifest_hash TEXT NOT NULL,
      planning_contract_id TEXT NOT NULL,
      planning_contract_version TEXT NOT NULL CHECK (planning_contract_version = '0.2'),
      phase_output_contract_id TEXT NOT NULL,
      phase_output_contract_version TEXT NOT NULL CHECK (phase_output_contract_version IN ('0.2', '0.3', '0.4',
        '0.5', '0.6', '0.7')),
      payload_sha256 TEXT NOT NULL,
      preplan_payload_json TEXT NOT NULL,
      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP$repairEvidence,
      UNIQUE(normalized_issue_key, repository_identity)
    )
    """.trimIndent()

  private fun issueKeyUniqueSubtaskPlansDdl(repairEvidence: String): String =
    """
    CREATE TABLE goal_subtask_plans (
      parent_goal_workflow_id TEXT NOT NULL,
      normalized_issue_key TEXT NOT NULL,
      repository_identity TEXT NOT NULL,
      subtask_id INTEGER NOT NULL CHECK (subtask_id > 0),
      manifest_order INTEGER NOT NULL CHECK (manifest_order >= 0),
      governed_sub_spec_path TEXT NOT NULL,
      sub_spec_hash TEXT NOT NULL,
      preparation_status TEXT NOT NULL CHECK (preparation_status = 'prepared'),
      contract_version TEXT NOT NULL CHECK (contract_version = '0.2'),
      parent_spec_hash TEXT NOT NULL,
      decomposition_manifest_hash TEXT NOT NULL,
      planning_contract_id TEXT NOT NULL,
      planning_contract_version TEXT NOT NULL CHECK (planning_contract_version = '0.2'),
      phase_output_contract_id TEXT NOT NULL,
      phase_output_contract_version TEXT NOT NULL CHECK (phase_output_contract_version IN ('0.2', '0.3', '0.4',
        '0.5', '0.6', '0.7')),
      payload_sha256 TEXT NOT NULL,
      plan_payload_json TEXT NOT NULL,
      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP$repairEvidence,
      PRIMARY KEY(parent_goal_workflow_id, subtask_id),
      UNIQUE(parent_goal_workflow_id, governed_sub_spec_path),
      UNIQUE(parent_goal_workflow_id, manifest_order),
      FOREIGN KEY(parent_goal_workflow_id) REFERENCES goal_shared_preplans(parent_goal_workflow_id) ON DELETE CASCADE
    )
    """.trimIndent()

  private fun seedPlanningRow(
    connection: Connection,
    phaseOutputContractVersion: String,
    workflowId: String = "wfl-planning",
    issueKey: String = "SKILL-000-$workflowId",
  ) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        INSERT INTO goal_shared_preplans (
          parent_goal_workflow_id, normalized_issue_key, repository_identity, preparation_status,
          contract_version, parent_spec_hash, decomposition_manifest_hash, planning_contract_id,
          planning_contract_version, phase_output_contract_id, phase_output_contract_version,
          payload_sha256, preplan_payload_json, created_at
        ) VALUES (
          '$workflowId', '$issueKey', 'repo', 'prepared', '0.2', 'spec-hash', 'manifest-hash',
          'goal-planning-preparation', '0.2', 'feature-task-runtime-phase-output', '$phaseOutputContractVersion',
          'payload-sha', '{}', CURRENT_TIMESTAMP
        )
        """.trimIndent(),
      )
      statement.execute(
        """
        INSERT INTO goal_subtask_plans (
          parent_goal_workflow_id, normalized_issue_key, repository_identity, subtask_id, manifest_order,
          governed_sub_spec_path, sub_spec_hash, preparation_status, contract_version, parent_spec_hash,
          decomposition_manifest_hash, planning_contract_id, planning_contract_version,
          phase_output_contract_id, phase_output_contract_version, payload_sha256, plan_payload_json, created_at
        ) VALUES (
          '$workflowId', '$issueKey', 'repo', 1, 0, '.feature-specs/x/spec_subtask_1.md', 'sub-hash',
          'prepared', '0.2', 'spec-hash', 'manifest-hash', 'goal-planning-preparation', '0.2',
          'feature-task-runtime-phase-output', '$phaseOutputContractVersion', 'payload-sha', '{}', CURRENT_TIMESTAMP
        )
        """.trimIndent(),
      )
    }
  }

  private fun scalar(
    connection: Connection,
    sql: String,
  ): Int =
    connection.createStatement().use { statement ->
      statement.executeQuery(sql).use { rows ->
        rows.next()
        rows.getInt(1)
      }
    }

  private fun textScalar(
    connection: Connection,
    sql: String,
  ): String =
    connection.createStatement().use { statement ->
      statement.executeQuery(sql).use { rows ->
        rows.next()
        rows.getString(1)
      }
    }

  private fun tableSql(
    connection: Connection,
    table: String,
  ): String =
    connection.prepareStatement(
      "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
    ).use { statement ->
      statement.setString(1, table)
      statement.executeQuery().use { rows ->
        rows.next()
        rows.getString(1)
      }
    }

  private fun tableHasColumn(
    connection: Connection,
    table: String,
    column: String,
  ): Boolean =
    connection.prepareStatement("SELECT 1 FROM pragma_table_info(?) WHERE name = ?").use { statement ->
      statement.setString(1, table)
      statement.setString(2, column)
      statement.executeQuery().use { rows -> rows.next() }
    }
}
