package skillbill.infrastructure.sqlite.workflow.featuretask

import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class StandalonePhaseStatusStoreTest {
  @Test
  fun `execution sequence and revision remain canonical when carrying into another digit`() {
    val directory = Files.createTempDirectory("standalone-status")
    val database = sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1"))
    val now = Instant.EPOCH
    val registration =
      StandalonePhaseStatusRegistration(
        repositoryIdentity = "repo", branchCorrelation = "main", issueKey = null, workflowId = null,
        invocationId = "run-1", phaseId = "review", executionId = "execution-1", lifecycleState = "active",
        currentStep = "review", startedAt = now, leaseOwner = "owner", leaseGeneration = 1,
        leaseExpiresAt = now.plusSeconds(3600),
      )
    repeat(10) { index ->
      val record =
        database.transaction {
          it.standalonePhaseStatuses.register(
            registration.copy(invocationId = "run-$index", executionId = "execution-$index"),
          )
        }
      assertEquals((index + 1).toString(), record.runSequence)
    }
    repeat(9) { index ->
      val result =
        database.transaction {
          it.standalonePhaseStatuses.update(
            StandalonePhaseStatusUpdate(
              executionId = "execution-9",
              expectedRevision = (index + 1).toString(),
              leaseOwner = "owner",
              leaseGeneration = 1,
              lifecycleState = "active",
              currentStep = "review",
              currentActivity = null,
              updatedAt = now,
            ),
          )
        }
      assertEquals(StandalonePhaseStatusUpdateResult.ACCEPTED, result)
    }
    val latest = database.read { it.standalonePhaseStatuses.readEligible("repo", "main", now).first() }
    assertEquals("10", latest.runSequence)
    assertEquals("10", latest.statusRevision)
  }

  @Test
  fun `a later monitor registration terminals a prior active monitor on the same branch`() {
    val directory = Files.createTempDirectory("standalone-monitor-supersede")
    val database = sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1"))
    val started = Instant.parse("2026-10-10T11:02:24Z")
    database.transaction {
      it.standalonePhaseStatuses.register(monitorRegistration("first", started, started.plusSeconds(3600)))
    }
    val secondStarted = Instant.parse("2026-10-10T12:27:39Z")
    database.transaction {
      it.standalonePhaseStatuses.register(
        monitorRegistration("second", secondStarted, Instant.parse("9999-12-31T00:00:00Z")),
      )
    }
    val rows =
      database.read {
        it.standalonePhaseStatuses.readEligible("repo", "feat/crashlytics", secondStarted)
      }
    assertEquals("terminal", rows.first { it.executionId == "execution-first" }.lifecycleState)
    assertEquals("active", rows.first { it.executionId == "execution-second" }.lifecycleState)
    assertEquals(
      "Superseded by a later monitor run.",
      rows.first { it.executionId == "execution-first" }.terminalResult,
    )
  }

  @Test
  fun `expired monitor leases are not paused by reconciliation`() {
    val directory = Files.createTempDirectory("standalone-monitor-lease")
    val database = sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1"))
    val started = Instant.parse("2026-10-10T11:02:24Z")
    val expired = Instant.parse("2026-10-10T11:02:25Z")
    database.transaction {
      it.standalonePhaseStatuses.register(monitorRegistration("watch", started, expired))
      it.standalonePhaseStatuses.register(
        monitorRegistration("review", started, expired).copy(
          phaseId = "review",
          invocationId = "run-review",
          executionId = "execution-review",
          currentStep = "review",
        ),
      )
    }
    val paused =
      database.transaction {
        it.standalonePhaseStatuses.reconcileExpiredLeases(Instant.parse("2026-10-10T12:00:00Z"))
      }
    assertEquals(1, paused)
    val later = Instant.parse("2026-10-10T12:00:00Z")
    val rows = database.read { it.standalonePhaseStatuses.readEligible("repo", "feat/crashlytics", later) }
    assertEquals("active", rows.first { it.executionId == "execution-watch" }.lifecycleState)
    assertEquals("paused", rows.first { it.executionId == "execution-review" }.lifecycleState)
  }

  private fun monitorRegistration(
    suffix: String,
    startedAt: Instant,
    leaseExpiresAt: Instant,
  ): StandalonePhaseStatusRegistration =
    StandalonePhaseStatusRegistration(
      repositoryIdentity = "repo",
      branchCorrelation = "feat/crashlytics",
      issueKey = null,
      workflowId = null,
      invocationId = "run-$suffix",
      phaseId = "monitor",
      executionId = "execution-$suffix",
      lifecycleState = "active",
      currentStep = "monitor",
      startedAt = startedAt,
      leaseOwner = "owner",
      leaseGeneration = 1,
      leaseExpiresAt = leaseExpiresAt,
    )
}
