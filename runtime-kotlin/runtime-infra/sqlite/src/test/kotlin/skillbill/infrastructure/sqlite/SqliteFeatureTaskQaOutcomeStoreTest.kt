package skillbill.infrastructure.sqlite

import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.recordQaFailureKind
import skillbill.infrastructure.sqlite.core.schema.DatabaseSchema
import skillbill.ports.featuretask.model.FeatureTaskQaFailureKind
import skillbill.ports.featuretask.model.FeatureTaskQaFinding
import skillbill.ports.featuretask.model.FeatureTaskQaFindingStatus
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.workflow.taskruntime.model.qa.QaAssertionOutcome
import skillbill.workflow.taskruntime.model.qa.QaRunAssertion
import skillbill.workflow.taskruntime.model.qa.QaRunCoverage
import skillbill.workflow.taskruntime.model.qa.QaRunDetails
import skillbill.workflow.taskruntime.model.qa.QaRunFinding
import skillbill.workflow.taskruntime.model.qa.QaRunRetainedResource
import skillbill.workflow.taskruntime.model.qa.QaRunTemporaryEdit
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
      assertNull(legacy.reportText)
      val findings =
        listOf(
          FeatureTaskQaFinding(
            "F-1",
            "Login button does nothing.",
            listOf("/qa/run-1/login.png"),
            "/qa/run-1/report.md",
            FeatureTaskQaFindingStatus.FIXED,
          ),
          FeatureTaskQaFinding(
            "F-2",
            "Home screen stays blank.",
            emptyList(),
            "/qa/run-4/report.md",
            FeatureTaskQaFindingStatus.NOT_FIXED,
          ),
        )

      store.upsert(legacy.copy(findings = findings))

      assertEquals(legacy.copy(findings = findings), store.find("wfl-goal"))
      assertEquals("/qa/run-4/report.md", store.find("wfl-goal")?.notFixedFindings?.single()?.reportPath)
    }
  }

  @Test
  fun `a QA outcome stores the report text verbatim and reads it back`() {
    val dbPath = Files.createTempDirectory("skillbill-qa-report-text").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).close()
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      val store = SqliteFeatureTaskQaOutcomeStore(connection)
      val reportText = "Verdict: incomplete\n\n## Summary\nThe login flow crashed on launch.\n\n" + "x".repeat(400_000)
      val outcome =
        FeatureTaskQaOutcome(
          "wfl-goal",
          StandaloneQaOutcome.INCOMPLETE,
          "The login flow crashed on launch.",
          0,
          "tree-1",
          "2026-10-20T16:14:00Z",
          reportText = reportText,
        )

      store.upsert(outcome)

      assertEquals(outcome, store.find("wfl-goal"))
    }
  }

  @Test
  fun `migration 66 adds a nullable report text column and keeps rows holding a legacy criteria summary`() {
    val dbPath = Files.createTempDirectory("skillbill-qa-report-text-migration").resolve("metrics.db")
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      DatabaseSchema.createBaseSchema(connection)
      DatabaseMigrations.apply(
        connection,
        SqliteTestDiagnostics,
        migrationSet = DatabaseMigrations.migrations.filter { it.version <= 65 },
      )
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "INSERT INTO feature_task_qa_outcomes (workflow_id, outcome, reason, qa_fix_attempts, " +
            "tested_tree_identity, recorded_at, criteria_summary) VALUES ('wfl-legacy', 'passed', NULL, 0, " +
            "NULL, '2026-10-20T10:15:30Z', " +
            "'[{\"id\":\"AC-001\",\"status\":\"passed\",\"checks\":[],\"findings\":[]}]')",
        )
      }
      assertTrue(!hasColumn(connection, "feature_task_qa_outcomes", "qa_report_text"))
    }

    DatabaseRuntime.migrateExistingDatabase(dbPath, SqliteTestDiagnostics)

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      assertTrue(hasColumn(connection, "feature_task_qa_outcomes", "qa_report_text"))
      assertTrue(hasColumn(connection, "standalone_phase_status", "qa_report_text"))
      val legacy = assertNotNull(SqliteFeatureTaskQaOutcomeStore(connection).find("wfl-legacy"))
      assertEquals(StandaloneQaOutcome.PASSED, legacy.outcome)
      assertNull(legacy.reportText)
    }
  }

  @Test
  fun `migration 84 keeps legacy rows readable, stores a failure kind and bumps the desktop revisions on QA writes`() {
    val dbPath = Files.createTempDirectory("skillbill-qa-failure-kind").resolve("metrics.db")
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      DatabaseSchema.createBaseSchema(connection)
      DatabaseMigrations.apply(
        connection,
        SqliteTestDiagnostics,
        migrationSet = DatabaseMigrations.migrations.filter { it.version <= 83 },
      )
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "INSERT INTO feature_task_qa_outcomes (workflow_id, outcome, reason, qa_fix_attempts, " +
            "tested_tree_identity, recorded_at) VALUES ('wfl-legacy', 'incomplete', 'old', 0, NULL, " +
            "'2026-10-20T10:15:30Z')",
        )
      }
      assertTrue(!hasColumn(connection, "feature_task_qa_outcomes", "failure_kind"))
    }

    DatabaseRuntime.migrateExistingDatabase(dbPath, SqliteTestDiagnostics)

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      val store = SqliteFeatureTaskQaOutcomeStore(connection)
      val legacy = assertNotNull(store.find("wfl-legacy"))
      assertNull(legacy.failureKind)
      val phasesBefore = revision(connection, "phases")
      val goalsBefore = revision(connection, "goals")

      store.upsert(legacy.copy(failureKind = FeatureTaskQaFailureKind.DEFECT))
      assertEquals(FeatureTaskQaFailureKind.DEFECT, store.find("wfl-legacy")?.failureKind)
      store.upsert(legacy.copy(failureKind = FeatureTaskQaFailureKind.ENVIRONMENT))

      assertEquals(FeatureTaskQaFailureKind.ENVIRONMENT, store.find("wfl-legacy")?.failureKind)
      assertTrue(revision(connection, "phases") > phasesBefore)
      assertTrue(revision(connection, "goals") > goalsBefore)
    }
  }

  @Test
  fun `running the failure kind migration again keeps exactly three QA outcome revision triggers`() {
    val dbPath = Files.createTempDirectory("skillbill-qa-failure-kind-rerun").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).close()
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      assertTrue(hasColumn(connection, "feature_task_qa_outcomes", "failure_kind"))

      recordQaFailureKind(connection)

      assertTrue(hasColumn(connection, "feature_task_qa_outcomes", "failure_kind"))
      assertEquals(3, qaOutcomeTriggerCount(connection))
    }
  }

  private fun qaOutcomeTriggerCount(connection: Connection): Int =
    connection.createStatement().use { statement ->
      statement.executeQuery(
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' " +
          "AND name LIKE 'desktop_change_feature_task_qa_outcomes_%'",
      ).use {
        it.next()
        it.getInt(1)
      }
    }

  private fun revision(
    connection: Connection,
    topic: String,
  ): Int =
    connection.prepareStatement("SELECT revision FROM desktop_change_revisions WHERE topic = ?").use { statement ->
      statement.setString(1, topic)
      statement.executeQuery().use {
        it.next()
        it.getInt(1)
      }
    }

  @Test
  fun `details and finding fields round trip, and a finding stored without the new keys keeps its title`() {
    val dbPath = Files.createTempDirectory("skillbill-qa-details").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).close()
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      val store = SqliteFeatureTaskQaOutcomeStore(connection)
      val outcome =
        FeatureTaskQaOutcome(
          "wfl-details",
          StandaloneQaOutcome.FINDINGS,
          null,
          0,
          null,
          "2026-10-20T10:15:30Z",
          listOf(
            FeatureTaskQaFinding(
              "QA-1",
              "Save fails",
              listOf("/qa/a.png"),
              "/qa/report.md",
              FeatureTaskQaFindingStatus.NOT_FIXED,
              "stays open",
              "closes",
              "tap save",
            ),
          ),
          details =
            QaRunDetails(
              reportPath = "/qa/report.md",
              invocationDirectory = "/qa",
              skipReason = "empty_scope",
              assertions =
                listOf(QaRunAssertion(QaAssertionOutcome.NOT_EXERCISED, "Offline", "failed", listOf("/qa/b.png"))),
              coverage = listOf(QaRunCoverage("web", "skipped", "empty_scope", "mocked")),
              retainedResources = listOf(QaRunRetainedResource("process", "48213")),
              temporaryEdits = listOf(QaRunTemporaryEdit(null, "changed", true)),
              findings =
                listOf(QaRunFinding("QA-1", "Save fails", "stays open", null, "tap save", listOf("/qa/a.png"))),
            ),
        )

      store.upsert(outcome)
      assertEquals(outcome, store.find("wfl-details"))

      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "UPDATE feature_task_qa_outcomes SET findings_summary = " +
            "'[{\"id\":\"QA-1\",\"observed\":\"Old title\",\"status\":\"not_fixed\"}]'",
        )
      }
      val legacy = assertNotNull(store.find("wfl-details")).findings.single()
      assertEquals("Old title", legacy.title)
      assertNull(legacy.observed)
      assertNull(legacy.expected)
      assertNull(legacy.steps)
    }
  }

  @Test
  fun `an unknown assertion outcome in stored details throws from the store`() {
    val dbPath = Files.createTempDirectory("skillbill-qa-details-bad").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).close()
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      val store = SqliteFeatureTaskQaOutcomeStore(connection)
      store.upsert(
        FeatureTaskQaOutcome("wfl-bad", StandaloneQaOutcome.PASSED, null, 0, null, "2026-10-20T10:15:30Z"),
      )
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "UPDATE feature_task_qa_outcomes SET qa_details = " +
            "'{\"assertions\":[{\"outcome\":\"bogus\",\"title\":\"t\",\"detail\":\"\"}]}'",
        )
      }

      assertFailsWith<SkillBillRuntimeException> { store.find("wfl-bad") }
    }
  }

  private fun hasColumn(
    connection: Connection,
    table: String,
    column: String,
  ): Boolean =
    connection.prepareStatement("SELECT 1 FROM pragma_table_info(?) WHERE name = ?").use { statement ->
      statement.setString(1, table)
      statement.setString(2, column)
      statement.executeQuery().use { it.next() }
    }
}
