package skillbill.cli.review

import skillbill.cli.core.CliRuntime
import skillbill.cli.decodeJsonObject
import skillbill.cli.model.CliRuntimeContext
import skillbill.engine.work.reviewReport
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.review.model.ReviewFindingStatus
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PrepareReviewCommandTest {
  @Test
  fun `preparing an older inline review lets feedback update that report and preserves it on repeated preparation`() {
    val home = Files.createTempDirectory("prepare-review")
    val path = home.resolve("selected.db")
    val database = sqliteSessionFactoryForTests(home, path.toString(), emptyMap())
    val text = "[F-007] Major | high | src/A.kt:9 | Broken boundary\nverdict: changes_requested"
    seedReviews(database, text)
    val context = CliRuntimeContext(environment = emptyMap(), userHome = home)
    val prepare = listOf("--db", path.toString(), "prepare-review", "--invocation-id", "original", "--format", "json")
    val prepared = CliRuntime.run(prepare, context)
    assertEquals(0, prepared.exitCode, prepared.stdout + prepared.stderr)
    assertEquals("run-original", decodeJsonObject(prepared.stdout)["review_run_id"])

    fun actionCommand(vararg args: String) = CliRuntime.run(listOf("--db", path.toString()) + args, context)
    val started =
      actionCommand("start-review-action", "--run-id", "run-original", "--action", "verify", "--format", "json")
    assertEquals(0, started.exitCode, started.stderr)
    val actionId = decodeJsonObject(started.stdout)["action_id"].toString()
    val partial =
      actionCommand("finish-review-action", "--run-id", "run-original", "--action-id", actionId, "--format", "json")
    assertEquals(0, partial.exitCode, partial.stderr)
    assertEquals(1, decodeJsonObject(partial.stdout)["open"])
    val fix = actionCommand("start-review-action", "--run-id", "run-original", "--action", "fix", "--format", "json")
    val fixId = decodeJsonObject(fix.stdout)["action_id"].toString()
    val feedback =
      CliRuntime.run(
        listOf(
          "--db", path.toString(), "record-feedback", "--run-id", "run-original", "--event", "fix_applied",
          "--finding", "F-007", "--note", "Source verification confirms the fix", "--format", "json",
        ),
        context,
      )
    assertEquals(0, feedback.exitCode, feedback.stdout + feedback.stderr)
    assertEquals(0, CliRuntime.run(prepare, context).exitCode)
    val finished =
      actionCommand("finish-review-action", "--run-id", "run-original", "--action-id", fixId, "--format", "json")
    assertEquals(0, finished.exitCode, finished.stderr)
    assertEquals(1, decodeJsonObject(finished.stdout)["fixed"])
    assertEquals(0, decodeJsonObject(finished.stdout)["open"])
    val replay =
      actionCommand("finish-review-action", "--run-id", "run-original", "--action-id", actionId, "--format", "json")
    assertEquals(1, decodeJsonObject(replay.stdout)["open"])
    assertEquals(1, actionCommand("start-review-action", "--run-id", "run-original", "--action", "verify").exitCode)
    database.read { unit ->
      val record = assertNotNull(unit.standalonePhaseStatuses.readByInvocation("original"))
      assertEquals(ReviewFindingStatus.FIXED, assertNotNull(reviewReport(record, text, unit)).findings.single().status)
      assertEquals(emptyMap(), unit.reviews.fetchFindingStatuses("run-later"))
      val actions = assertNotNull(reviewReport(record, text, unit)).findingActions
      assertEquals(listOf(1, 0), actions.map { it.open })
      assertEquals(listOf(0, 1), actions.map { it.fixed })
    }
  }

  private fun seedReviews(
    database: DatabaseSessionFactory,
    text: String,
  ) {
    val at = Instant.parse("2026-11-01T09:00:00Z")
    database.transaction { unit ->
      listOf("original", "later").forEach { id ->
        val record =
          unit.standalonePhaseStatuses.register(
            StandalonePhaseStatusRegistration(
              repositoryIdentity = "repo", branchCorrelation = "branch", issueKey = null, workflowId = null,
              invocationId = id, phaseId = "review", executionId = "execution-$id", lifecycleState = "active",
              currentStep = "present_findings", startedAt = at,
              leaseOwner = "owner", leaseGeneration = 1, leaseExpiresAt = at,
            ),
          )
        unit.standalonePhaseStatuses.update(
          StandalonePhaseStatusUpdate(
            executionId = record.executionId, expectedRevision = record.statusRevision,
            leaseOwner = "owner", leaseGeneration = 1,
            lifecycleState = "terminal", currentStep = "present_findings", currentActivity = null, updatedAt = at,
            finishedAt = at, terminalResult = text, acceptedResult = text, reviewRunId = "run-$id",
          ),
        )
      }
    }
  }
}
