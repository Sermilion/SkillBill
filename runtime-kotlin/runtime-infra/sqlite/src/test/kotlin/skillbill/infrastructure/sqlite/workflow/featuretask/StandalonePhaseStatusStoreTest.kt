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
  fun `monitor registration extends its lease without changing ordinary phase expiry`() {
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
    val later = Instant.parse("2026-10-10T12:00:00Z")
    val rows = database.read { it.standalonePhaseStatuses.readEligible("repo", "feat/crashlytics", later) }
    assertEquals("active", rows.first { it.executionId == "execution-watch" }.lifecycleState)
    assertEquals(
      Instant.parse("9999-12-31T00:00:00Z"),
      rows.first { it.executionId == "execution-watch" }.leaseExpiresAt,
    )
    assertEquals(expired, rows.first { it.executionId == "execution-review" }.leaseExpiresAt)
  }

  @Test
  fun `board candidate read keeps live rows across identities and completion bounds inclusive`() {
    val directory = Files.createTempDirectory("standalone-board-candidates")
    val database = sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1"))
    val finishedFrom = Instant.parse("2026-10-10T00:00:00Z")
    val finishedThrough = Instant.parse("2026-10-11T00:00:00Z")
    val registrationTime = finishedThrough.minusSeconds(300)
    val names = listOf("live-old", "lower-bound", "upper-bound", "outside")
    database.transaction {
      names.forEachIndexed { index, name ->
        val rootIdentity = if (index % 2 == 0) "repo-a" else "repo-b"
        it.standalonePhaseStatuses.register(
          StandalonePhaseStatusRegistration(
            repositoryIdentity = rootIdentity,
            branchCorrelation = "branch-$index",
            issueKey = null,
            workflowId = null,
            invocationId = name,
            phaseId = "review",
            executionId = "execution-$name",
            lifecycleState = "active",
            currentStep = "review",
            startedAt = registrationTime.minusSeconds(90_000),
            leaseOwner = "owner-$name",
            leaseGeneration = 1,
            leaseExpiresAt = finishedThrough.plusSeconds(86_400),
          ),
        )
        if (name != "live-old") {
          val finishedAt =
            when (name) {
              "lower-bound" -> finishedFrom
              "upper-bound" -> finishedThrough
              else -> finishedFrom.minusNanos(1)
            }
          it.standalonePhaseStatuses.update(
            StandalonePhaseStatusUpdate(
              executionId = "execution-$name",
              expectedRevision = "1",
              leaseOwner = "owner-$name",
              leaseGeneration = 1,
              lifecycleState = "terminal",
              currentStep = "review",
              currentActivity = null,
              updatedAt = finishedAt,
              finishedAt = finishedAt,
              terminalResult = "done",
            ),
          )
        }
      }
    }

    val (candidates, exactOld) =
      database.read {
        it.standalonePhaseStatuses.readBoardCandidates(finishedFrom, finishedThrough) to
          it.standalonePhaseStatuses.readByInvocation("outside")
      }

    assertEquals(setOf("live-old", "lower-bound", "upper-bound"), candidates.map { it.invocationId }.toSet())
    assertEquals("outside", exactOld?.invocationId)
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
