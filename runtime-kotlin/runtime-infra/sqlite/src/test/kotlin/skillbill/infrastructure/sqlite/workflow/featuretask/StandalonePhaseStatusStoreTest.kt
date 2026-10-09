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
}
