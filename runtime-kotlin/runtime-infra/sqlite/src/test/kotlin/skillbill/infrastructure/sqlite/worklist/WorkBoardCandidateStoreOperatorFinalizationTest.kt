package skillbill.infrastructure.sqlite.worklist

import skillbill.infrastructure.sqlite.core.schema.OPERATOR_FINALIZED_AT_COLUMN
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.idestatus.StandalonePhaseStatusRepository
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.work.model.WorkBoardCandidateQuery
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkBoardCandidateStoreOperatorFinalizationTest {
  @Test
  fun `unfinalized completed standalone stays eligible after settled retention`() {
    val directory = Files.createTempDirectory("work-board-candidate-finalization")
    val database = sqliteDatabaseSessionFactory(userHome = directory, environment = mapOf("TEST" to "1"))
    val now = Instant.parse("2026-10-11T00:00:00Z")
    val finishedAt = now.minus(Duration.ofHours(8))
    database.transaction {
      persistTerminal(it.standalonePhaseStatuses, "done-unfinalized", "validate", finishedAt, now)
      persistTerminal(it.standalonePhaseStatuses, "done-finalized", "review", finishedAt, now)
    }
    val finishedFrom = now.minus(Duration.ofHours(6))
    val candidates =
      DriverManager.getConnection("jdbc:sqlite:${database.resolveDbPath()}").use { connection ->
        markFinalized(connection, "done-finalized")
        insertVerify(connection, "verify-unfinalized", finishedAt, operatorFinalizedAt = null)
        insertVerify(connection, "verify-finalized", finishedAt, operatorFinalizedAt = finishedAt)
        readWorkBoardCandidates(connection, WorkBoardCandidateQuery(finishedFrom, now, null, 50))
      }
    assertEquals(
      listOf("done-unfinalized", "verify-unfinalized").sorted(),
      candidates.map { it.id }.sorted(),
    )
  }

  private fun persistTerminal(
    statuses: StandalonePhaseStatusRepository,
    invocationId: String,
    phaseId: String,
    finishedAt: Instant,
    now: Instant,
  ) {
    statuses.register(
      StandalonePhaseStatusRegistration(
        repositoryIdentity = "repo",
        branchCorrelation = "main",
        issueKey = null,
        workflowId = null,
        invocationId = invocationId,
        phaseId = phaseId,
        executionId = "execution-$invocationId",
        lifecycleState = "active",
        currentStep = phaseId,
        startedAt = finishedAt.minusSeconds(60),
        leaseOwner = "owner",
        leaseGeneration = 1,
        leaseExpiresAt = now.plusSeconds(3600),
      ),
    )
    statuses.update(
      StandalonePhaseStatusUpdate(
        executionId = "execution-$invocationId",
        expectedRevision = "1",
        leaseOwner = "owner",
        leaseGeneration = 1,
        lifecycleState = "terminal",
        currentStep = phaseId,
        currentActivity = null,
        updatedAt = finishedAt,
        finishedAt = finishedAt,
        terminalResult = "done",
      ),
    )
  }

  private fun markFinalized(
    connection: Connection,
    invocationId: String,
  ) {
    connection.prepareStatement(
      "UPDATE standalone_phase_status SET $OPERATOR_FINALIZED_AT_COLUMN = finished_at WHERE invocation_id = ?",
    ).use { statement ->
      statement.setString(1, invocationId)
      statement.executeUpdate()
    }
  }

  private fun insertVerify(
    connection: Connection,
    workflowId: String,
    finishedAt: Instant,
    operatorFinalizedAt: Instant?,
  ) {
    connection.prepareStatement(
      """
      INSERT INTO feature_verify_workflows (
        workflow_id, workflow_status, started_at, finished_at, $OPERATOR_FINALIZED_AT_COLUMN
      ) VALUES (?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setString(2, WorkflowStatus.COMPLETED.wireValue)
      statement.setString(3, finishedAt.minusSeconds(60).toString())
      statement.setString(4, finishedAt.toString())
      statement.setString(5, operatorFinalizedAt?.toString())
      statement.executeUpdate()
    }
  }
}
