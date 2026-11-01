package skillbill.infrastructure.sqlite

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.GovernedReviewFailureCode
import skillbill.goalrunner.model.ReviewFindingOutcome
import skillbill.goalrunner.model.ReviewFindingOutcomeRecord
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.review.model.ReviewClaimVerdict.REFUTED
import skillbill.review.model.ReviewFindingCitation
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnaddressedFindingsRuntimeTest {
  @Test
  fun `goal existence comes from durable workflows without telemetry rows`() {
    val dbPath = Files.createTempDirectory("unaddressed-findings-goal-authority").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.prepareStatement(
        "INSERT INTO feature_task_workflows " +
          "(workflow_id, workflow_name, mode, contract_version, issue_key) VALUES (?, ?, ?, ?, ?)",
      ).use { statement ->
        statement.setString(1, "goal-parent-1")
        statement.setString(2, "bill-feature-task")
        statement.setString(3, "runtime")
        statement.setString(4, "0.2")
        statement.setString(5, "SKILL-135")
        statement.executeUpdate()
      }
      val repository = SQLiteUnaddressedFindingsRepository(connection)

      assertTrue(repository.issueExists("SKILL-135"))
      assertFalse(repository.issueExists("SKILL-404"))
    }
  }

  @Test
  fun `re-recording a review pass replaces its ledger rows`() {
    val dbPath = Files.createTempDirectory("unaddressed-findings").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val repository = SQLiteUnaddressedFindingsRepository(connection)
      val first = finding(1, "minor", "src/First.kt:7")
      val replacement = finding(1, "major", "src/Second.kt:9")

      repository.replaceLedgerForPass("workflow-1", 1, listOf(first))
      repository.replaceLedgerForPass("workflow-1", 1, listOf(replacement))

      assertEquals(listOf(replacement), repository.fetchLedger("SKILL-135"))
    }
  }

  @Test
  fun `a later review pass supersedes findings an earlier pass reported`() {
    val dbPath = Files.createTempDirectory("unaddressed-findings-later-pass").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val repository = SQLiteUnaddressedFindingsRepository(connection)
      val addressedBlocker = finding(1, "blocker", "src/First.kt:7")
      val deferred = finding(2, "minor", "src/Deferred.kt:8")
      repository.replaceLedgerForPass("workflow-1", 1, listOf(addressedBlocker, deferred))
      val stillOpen = finding(1, "minor", "src/Deferred.kt:8").copy(reviewPassNumber = 2)

      repository.replaceLedgerForPass("workflow-1", 2, listOf(stillOpen))

      assertEquals(listOf(stillOpen), repository.fetchLedger("SKILL-135"))
    }
  }

  @Test
  fun `an approving later pass retracts every finding the fix loop addressed`() {
    val dbPath = Files.createTempDirectory("unaddressed-findings-approving-pass").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val repository = SQLiteUnaddressedFindingsRepository(connection)
      repository.replaceLedgerForPass(
        "workflow-1",
        1,
        listOf(finding(1, "blocker", "src/First.kt:7"), finding(2, "minor", "src/Deferred.kt:8")),
      )

      repository.replaceLedgerForPass("workflow-1", 2, emptyList())

      assertEquals(emptyList(), repository.fetchLedger("SKILL-135"))
    }
  }

  @Test
  fun `superseding one workflow leaves a sibling subtask's findings intact`() {
    val dbPath = Files.createTempDirectory("unaddressed-findings-sibling").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val repository = SQLiteUnaddressedFindingsRepository(connection)
      val sibling = finding(1, "major", "src/Sibling.kt:3").copy(workflowId = "workflow-2", subtaskId = 2)
      repository.replaceLedgerForPass("workflow-1", 1, listOf(finding(1, "blocker", "src/First.kt:7")))
      repository.replaceLedgerForPass("workflow-2", 1, listOf(sibling))

      repository.replaceLedgerForPass("workflow-1", 2, emptyList())

      assertEquals(listOf(sibling), repository.fetchLedger("SKILL-135"))
    }
  }

  @Test
  fun `clearing a workflow ledger drops every pass of that workflow and spares its siblings`() {
    val dbPath = Files.createTempDirectory("unaddressed-findings-clear-workflow").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val repository = SQLiteUnaddressedFindingsRepository(connection)
      val sibling = finding(1, "major", "src/Sibling.kt:3").copy(workflowId = "workflow-2", subtaskId = 2)
      repository.replaceLedgerForPass("workflow-1", 1, listOf(finding(1, "blocker", "src/First.kt:7")))
      repository.replaceLedgerForPass(
        "workflow-1",
        2,
        listOf(finding(1, "blocker", "src/Second.kt:9").copy(reviewPassNumber = 2)),
      )
      repository.replaceLedgerForPass("workflow-2", 1, listOf(sibling))

      repository.clearWorkflowLedger("workflow-1")

      assertEquals(listOf(sibling), repository.fetchLedger("SKILL-135"))
    }
  }

  @Test
  fun `the shared review run key round-trips and stays null when no review run was imported`() {
    val dbPath = Files.createTempDirectory("unaddressed-findings-key").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val repository = SQLiteUnaddressedFindingsRepository(connection)
      val keyed =
        finding(1, "blocker", "src/First.kt:7").copy(
          reviewRunId = "rvw-1",
          findingId = "F-001",
          claimVerdict = REFUTED,
          citations = listOf(ReviewFindingCitation("src/First.kt", 7)),
        )
      val unkeyed = finding(2, "minor", "src/Second.kt:9")
      connection.prepareStatement(
        "INSERT INTO review_runs (review_run_id, review_session_id, raw_text) VALUES (?, ?, ?)",
      ).use { statement ->
        statement.setString(1, "rvw-1")
        statement.setString(2, "session-1")
        statement.setString(3, "review")
        statement.executeUpdate()
      }

      repository.replaceLedgerForPass("workflow-1", 1, listOf(keyed, unkeyed))

      val ledger = repository.fetchLedger("SKILL-135")
      assertEquals(listOf(keyed, unkeyed), ledger, "Both the keyed and the unkeyed row must survive verbatim.")
      assertEquals(null, ledger[1].reviewRunId, "An unimported pass must not be bucketed to a guessed run.")
      assertEquals(null, ledger[1].findingId)
    }
  }

  @Test
  fun `a ledger pass rejects review run ids without a parent before deleting existing rows`() {
    val dbPath = Files.createTempDirectory("unaddressed-findings-parent").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val repository = SQLiteUnaddressedFindingsRepository(connection)
      val existing = finding(1, "blocker", "src/First.kt:7")
      repository.replaceLedgerForPass("workflow-1", 1, listOf(existing))
      val dangling = existing.copy(reviewRunId = "missing-review-run")

      val failure =
        assertFailsWith<SkillBillRuntimeException> {
          repository.replaceLedgerForPass("workflow-1", 2, listOf(dangling))
        }
      assertEquals(GovernedReviewFailureCode.REVIEW_RUN_PARENT_MISSING, failure.code)

      assertEquals(listOf(existing), repository.fetchLedger("SKILL-135"))
    }
  }

  @Test
  fun `recorded outcomes survive ledger retraction and clearing`() {
    val dbPath = Files.createTempDirectory("unaddressed-findings-outcomes").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val repository = SQLiteUnaddressedFindingsRepository(connection)
      val outcomes =
        listOf(
          ReviewFindingOutcomeRecord("workflow-1", 1, 1, ReviewFindingOutcome.ADDRESSED, "rvw-1", "F-001"),
          ReviewFindingOutcomeRecord("workflow-1", 1, 2, ReviewFindingOutcome.CARRIED),
        )
      repository.replaceLedgerForPass("workflow-1", 1, listOf(finding(1, "blocker", "src/First.kt:7")))
      repository.recordOutcomes(outcomes)

      repository.clearWorkflowLedger("workflow-1")

      assertEquals(emptyList(), repository.fetchLedger("SKILL-135"))
      assertEquals(outcomes, repository.fetchOutcomes("workflow-1"), "Outcomes must outlive the ledger.")
    }
  }

  @Test
  fun `re-recording an outcome for the same finding upserts rather than duplicating`() {
    val dbPath = Files.createTempDirectory("unaddressed-findings-outcome-upsert").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val repository = SQLiteUnaddressedFindingsRepository(connection)
      repository.recordOutcomes(listOf(ReviewFindingOutcomeRecord("workflow-1", 1, 1, ReviewFindingOutcome.CARRIED)))

      repository.recordOutcomes(listOf(ReviewFindingOutcomeRecord("workflow-1", 1, 1, ReviewFindingOutcome.ADDRESSED)))

      assertEquals(
        listOf(ReviewFindingOutcomeRecord("workflow-1", 1, 1, ReviewFindingOutcome.ADDRESSED)),
        repository.fetchOutcomes("workflow-1"),
        "A later pass must correct the earlier outcome in place, not append a second row.",
      )
    }
  }

  @Test
  fun `a terminal outcome reconciles every earlier carried pass for the same finding`() {
    val dbPath = Files.createTempDirectory("unaddressed-findings-cross-pass").resolve("runtime.db")
    val key = "src/outbox.kt:12|ambiguous outbox error signal"
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val repository = SQLiteUnaddressedFindingsRepository(connection)
      repository.recordOutcomes(
        listOf(ReviewFindingOutcomeRecord("workflow-1", 1, 1, ReviewFindingOutcome.CARRIED, "rvw-1", "F-003", key)),
      )
      repository.recordOutcomes(
        listOf(ReviewFindingOutcomeRecord("workflow-1", 2, 1, ReviewFindingOutcome.CARRIED, "rvw-2", "F-002", key)),
      )

      repository.recordOutcomes(
        listOf(ReviewFindingOutcomeRecord("workflow-1", 3, 1, ReviewFindingOutcome.ADDRESSED, "rvw-3", "F-001", key)),
      )

      assertEquals(
        listOf(ReviewFindingOutcome.ADDRESSED, ReviewFindingOutcome.ADDRESSED, ReviewFindingOutcome.ADDRESSED),
        repository.fetchOutcomes("workflow-1").map(ReviewFindingOutcomeRecord::outcome),
      )
      assertEquals(
        listOf("rvw-1", "rvw-2", "rvw-3"),
        repository.fetchOutcomes("workflow-1").map(ReviewFindingOutcomeRecord::reviewRunId),
        "Each pass keeps its own review run; only the outcome is reconciled.",
      )
    }
  }

  @Test
  fun `reconciliation does not touch another workflow or another finding sharing a finding id`() {
    val dbPath = Files.createTempDirectory("unaddressed-findings-cross-pass-scope").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val repository = SQLiteUnaddressedFindingsRepository(connection)
      repository.recordOutcomes(
        listOf(
          ReviewFindingOutcomeRecord("workflow-1", 1, 1, ReviewFindingOutcome.CARRIED, "rvw-1", "F-001", "other"),
          ReviewFindingOutcomeRecord("workflow-2", 1, 1, ReviewFindingOutcome.CARRIED, "rvw-1", "F-001", "fixed"),
        ),
      )

      repository.recordOutcomes(
        listOf(
          ReviewFindingOutcomeRecord("workflow-1", 2, 1, ReviewFindingOutcome.ADDRESSED, "rvw-2", "F-001", "fixed"),
        ),
      )

      assertEquals(
        listOf(ReviewFindingOutcome.CARRIED, ReviewFindingOutcome.ADDRESSED),
        repository.fetchOutcomes("workflow-1").map(ReviewFindingOutcomeRecord::outcome),
      )
      assertEquals(
        listOf(ReviewFindingOutcome.CARRIED),
        repository.fetchOutcomes("workflow-2").map(ReviewFindingOutcomeRecord::outcome),
      )
    }
  }

  @Test
  fun `a keyed outcome joins to its review run`() {
    val dbPath = Files.createTempDirectory("unaddressed-findings-join").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.prepareStatement(
        "INSERT INTO review_runs (review_run_id, routed_skill, raw_text) VALUES (?, ?, ?)",
      ).use { statement ->
        statement.setString(1, "rvw-1")
        statement.setString(2, "bill-kotlin-code-review")
        statement.setString(3, "")
        statement.executeUpdate()
      }
      val repository = SQLiteUnaddressedFindingsRepository(connection)
      repository.recordOutcomes(
        listOf(
          ReviewFindingOutcomeRecord("workflow-1", 1, 1, ReviewFindingOutcome.ADDRESSED, "rvw-1", "F-001"),
          ReviewFindingOutcomeRecord("workflow-1", 1, 2, ReviewFindingOutcome.CARRIED),
        ),
      )

      val routed =
        connection.createStatement().use { statement ->
          statement.executeQuery(
            """
            SELECT o.finding_ordinal, r.routed_skill
            FROM review_finding_outcomes o
            JOIN review_runs r ON r.review_run_id = o.review_run_id
            ORDER BY o.finding_ordinal
            """.trimIndent(),
          ).use { rows ->
            buildList { while (rows.next()) add(rows.getInt(1) to rows.getString(2)) }
          }
        }

      assertEquals(
        listOf(1 to "bill-kotlin-code-review"),
        routed,
        "The keyed outcome must resolve to its routed pack; the unresolved one must not join at all.",
      )
    }
  }

  private fun finding(
    ordinal: Int,
    severity: String,
    location: String,
  ) = UnaddressedFinding(
    issueKey = "SKILL-135",
    subtaskId = 3,
    workflowId = "workflow-1",
    reviewPassNumber = 1,
    findingOrdinal = ordinal,
    severity = severity,
    issueCategory = "data_persistence",
    location = location,
    summary = "Finding $ordinal",
  )
}
