package skillbill.infrastructure.sqlite

import skillbill.infrastructure.sqlite.core.migration.DatabaseMigrations
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.core.schema.DatabaseSchema
import skillbill.ports.featuretask.model.FeatureTaskQaFinding
import skillbill.ports.featuretask.model.FeatureTaskQaFindingStatus
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaCheck
import skillbill.ports.idestatus.model.StandaloneQaCriterion
import skillbill.ports.idestatus.model.StandaloneQaCriterionFinding
import skillbill.ports.idestatus.model.StandaloneQaCriterionStatus
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaReport
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SqliteFeatureTaskQaOutcomeStoreTest {
  @Test
  fun `a QA outcome recorded before the findings summary keeps reading and then stores its findings`() {
    val dbPath = Files.createTempDirectory("skillbill-qa-findings-summary").resolve("metrics.db")
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      DatabaseSchema.createBaseSchema(connection)
      DatabaseMigrations.apply(
        connection,
        SqliteTestDiagnostics,
        migrationSet = DatabaseMigrations.migrations.filter { it.version <= 61 },
      )
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "INSERT INTO feature_task_qa_outcomes (workflow_id, outcome, reason, qa_fix_attempts, " +
            "tested_tree_identity, recorded_at) VALUES ('wfl-goal', 'findings', 'findings left open: F-2', 3, " +
            "NULL, '2026-10-20T10:15:30Z')",
        )
      }
    }

    DatabaseRuntime.migrateExistingDatabase(dbPath, SqliteTestDiagnostics)

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      val store = SqliteFeatureTaskQaOutcomeStore(connection)
      val legacy = assertNotNull(store.find("wfl-goal"))
      assertEquals(StandaloneQaOutcome.FINDINGS, legacy.outcome)
      assertEquals(emptyList(), legacy.findings)
      val findings =
        listOf(
          FeatureTaskQaFinding(
            "F-1",
            "Login button does nothing.",
            listOf("AC-001"),
            listOf("/qa/run-1/login.png"),
            "/qa/run-1/receipt.json",
            FeatureTaskQaFindingStatus.FIXED,
          ),
          FeatureTaskQaFinding(
            "F-2",
            "Home screen stays blank.",
            listOf("AC-001", "AC-002"),
            emptyList(),
            "/qa/run-4/receipt.json",
            FeatureTaskQaFindingStatus.NOT_FIXED,
          ),
        )

      store.upsert(legacy.copy(findings = findings))

      assertEquals(legacy.copy(findings = findings), store.find("wfl-goal"))
      assertEquals("/qa/run-4/receipt.json", store.find("wfl-goal")?.notFixedFindings?.single()?.evidencePath)
    }
  }

  @Test
  fun `a QA outcome stores the per-criterion report and reads it back`() {
    val dbPath = Files.createTempDirectory("skillbill-qa-criteria-summary").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).close()
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      val store = SqliteFeatureTaskQaOutcomeStore(connection)
      val report =
        StandaloneQaReport(
          listOf(
            StandaloneQaCriterion(
              "AC-001",
              StandaloneQaCriterionStatus.FAILED,
              listOf(StandaloneQaCheck("Retained field values survive a restart.", false)),
              listOf(
                StandaloneQaCriterionFinding(
                  "F-1",
                  listOf("Restart the app"),
                  "Values are still there.",
                  "Values are empty.",
                ),
              ),
            ),
            StandaloneQaCriterion(
              "AC-002",
              StandaloneQaCriterionStatus.PASSED,
              listOf(StandaloneQaCheck("The pause flag is written with run state.", true)),
              emptyList(),
            ),
          ),
        )
      val outcome =
        FeatureTaskQaOutcome(
          "wfl-goal",
          StandaloneQaOutcome.INCOMPLETE,
          "other · The login flow crashed on launch.",
          0,
          "tree-1",
          "2026-10-20T16:14:00Z",
          report = report,
        )
      store.upsert(outcome)
      assertEquals(outcome, store.find("wfl-goal"))
    }
  }
}
