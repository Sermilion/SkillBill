package skillbill.cli.qa

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parsers.CommandLineParser
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.model.CliRunInputs
import skillbill.engine.featuretask.phaserun.qa.InMemoryQaWorkspaces
import skillbill.engine.featuretask.qa.checklist.QaChecklistRecorder
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.infrastructure.workflow.qa.FileSystemQaChecklistPlanFile
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandaloneQaChecklist
import skillbill.ports.idestatus.model.StandaloneQaChecklistCase
import skillbill.ports.idestatus.model.StandaloneQaChecklistStep
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.qa.QaDeviceLeasePort
import skillbill.ports.qa.QaOwnedResourcePort
import skillbill.ports.qa.model.QaDeviceLease
import skillbill.ports.qa.model.QaDeviceLeaseSnapshot
import skillbill.ports.qa.model.QaLeaseReleaseOutcome
import skillbill.ports.qa.model.QaLeaseResult
import skillbill.ports.qa.model.QaOwnedResource
import skillbill.ports.qa.model.QaResourceRegistration
import skillbill.ports.qa.model.QaResourceReleaseOutcome
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class QaProgressCommandTest {
  private val database =
    sqliteDatabaseSessionFactory(Files.createTempDirectory("qa-progress"), environment = mapOf("TEST" to "1"))
  private val recorder =
    QaChecklistRecorder(database, Clock.systemUTC(), NoopRuntimeDiagnostics, InMemoryQaWorkspaces())

  @Test
  fun `qa root exposes plan step and target progress commands`() {
    val commands =
      listOf(
        listOf("plan", "--invocation", RUN, "--file", "/missing/qa-plan.json") to "failed",
        listOf(
          "step", "--invocation", RUN, "--case", "login", "--step", "1", "--status", "passed",
        ) to "rejected",
        listOf("target", "--invocation", RUN, "--name", "web", "--state", "ready") to "rejected",
      )

    commands.forEach { (arguments, expectedStatus) ->
      val state = CliRunState(stdinText = null)
      CommandLineParser.parseAndRun(commandTree(state), arguments) { parsed -> parsed.run() }

      val result = checkNotNull(state.result)
      assertEquals(0, result.exitCode)
      assertEquals(expectedStatus, result.payload?.get("status"))
    }
  }

  @Test
  fun `a dropped evidence path reports it, exits 0, and the next step still records`() {
    registerRow()
    val state =
      step(recorder, "--step", "1", "--status", "passed", "--evidence", "/elsewhere/a.png", "--evidence", "/qa/a.png")

    val result = checkNotNull(state.result)
    assertEquals(0, result.exitCode)
    assertEquals("recorded", result.payload?.get("status"))
    assertEquals(listOf("/elsewhere/a.png"), result.payload?.get("rejected_evidence"))
    val stored = stored().cases.single().steps
    assertEquals(listOf("/qa/a.png"), stored[0].evidence)
    val next = checkNotNull(step(recorder, "--step", "2", "--status", "running").result)
    assertEquals("recorded", next.payload?.get("status"))
  }

  @Test
  fun `a finished run prints rejected for step and target, exits 0 and stays unchanged`() {
    registerRow()
    val before = stored()
    val row = checkNotNull(database.read { it.standalonePhaseStatuses.readByInvocation(RUN) })
    database.transaction {
      it.standalonePhaseStatuses.update(
        StandalonePhaseStatusUpdate(
          executionId = row.executionId, expectedRevision = row.statusRevision, leaseOwner = row.leaseOwner,
          leaseGeneration = row.leaseGeneration, lifecycleState = "canceled", currentStep = "qa",
          currentActivity = null, updatedAt = Instant.EPOCH.plusSeconds(1), finishedAt = Instant.EPOCH.plusSeconds(1),
          terminalResult = "canceled",
        ),
      )
    }

    val step = checkNotNull(step(recorder, "--step", "1", "--status", "passed").result)
    val target =
      checkNotNull(
        run({ QaTargetCommand(recorder, it) }, "--invocation", RUN, "--name", "web", "--state", "ready").result,
      )

    listOf(step, target).forEach { result ->
      assertEquals(0, result.exitCode)
      assertEquals("rejected", result.payload?.get("status"))
    }
    assertEquals(before, stored())
  }

  @Test
  fun `a database failure prints failed and exits 0`() {
    val broken =
      QaChecklistRecorder(FailingDatabase(database), Clock.systemUTC(), NoopRuntimeDiagnostics, InMemoryQaWorkspaces())

    val result = checkNotNull(step(broken, "--step", "1", "--status", "passed").result)

    assertEquals(0, result.exitCode)
    assertEquals("failed", result.payload?.get("status"))
  }

  @Test
  fun `an unreadable plan prints failed and an oversized plan prints rejected, both exit 0`() {
    val directory = Files.createTempDirectory("qa-plan")
    val oversized = directory.resolve("big.json")
    Files.write(oversized, ByteArray(512 * 1024 + 1))

    val cases =
      listOf(directory.resolve("absent.json").toString() to "failed", oversized.toString() to "rejected")

    cases.forEach { (path, status) ->
      val result =
        checkNotNull(
          run(
            { QaPlanCommand(recorder, it, FileSystemQaChecklistPlanFile()) },
            "--invocation",
            RUN,
            "--file",
            path,
          ).result,
        )
      assertEquals(0, result.exitCode)
      assertEquals(status, result.payload?.get("status"))
    }
  }

  private fun step(
    recorder: QaChecklistRecorder,
    vararg arguments: String,
  ): CliRunState = run({ state -> QaStepCommand(recorder, state) }, "--invocation", RUN, "--case", "login", *arguments)

  private fun run(
    command: (CliRunState) -> CliktCommand,
    vararg arguments: String,
  ): CliRunState {
    val state = CliRunState(stdinText = null)
    CommandLineParser.parseAndRun(command(state), arguments.toList()) { parsed -> parsed.run() }
    return state
  }

  private fun commandTree(state: CliRunState): QaCommand {
    val inputs =
      CliRunInputs(
        databasePath = null,
        environment = emptyMap(),
        userHome = Path.of("/tmp"),
        repositoryRoot = Path.of("/tmp/repo"),
        liveStdout = {},
        liveStderr = {},
      )
    return QaCommand(
      QaLeaseCommand(FakeLeasePort, state, inputs),
      QaResourceCommand(FakeResourcePort, state, inputs),
      QaPlanCommand(recorder, state, FileSystemQaChecklistPlanFile()),
      QaStepCommand(recorder, state),
      QaTargetCommand(recorder, state),
      QaStageCommand(recorder, state),
      QaScreenshotCommand(recorder, state, inputs),
    )
  }

  private fun stored(): StandaloneQaChecklist =
    checkNotNull(database.read { it.standalonePhaseStatuses.readByInvocation(RUN) }?.qaChecklist)

  private fun registerRow() {
    database.transaction {
      val statuses = it.standalonePhaseStatuses
      statuses.register(
        StandalonePhaseStatusRegistration(
          repositoryIdentity = "repo", branchCorrelation = "main", issueKey = null, workflowId = null,
          invocationId = RUN, phaseId = SkeletonDefinition.QA.id, executionId = "execution",
          lifecycleState = "active", currentStep = "qa", startedAt = Instant.EPOCH, leaseOwner = "owner",
          leaseGeneration = 1, leaseExpiresAt = Instant.EPOCH.plusSeconds(3600),
        ),
      )
      statuses.writeQaChecklist(
        RUN,
        StandaloneQaChecklist(
          invocationDirectory = "/qa",
          cases =
            listOf(
              StandaloneQaChecklistCase(
                "login",
                "Login",
                listOf(StandaloneQaChecklistStep("first"), StandaloneQaChecklistStep("second")),
              ),
            ),
        ),
      )
    }
  }

  private class FailingDatabase(delegate: DatabaseSessionFactory) : DatabaseSessionFactory by delegate {
    override fun <T> transaction(block: (UnitOfWork) -> T): T = error("database is locked")
  }

  private object FakeLeasePort : QaDeviceLeasePort {
    override fun liveHolders(repoRoot: Path): QaDeviceLeaseSnapshot = QaDeviceLeaseSnapshot.Holders(emptyList())

    override fun acquire(
      repoRoot: Path,
      deviceFingerprint: String,
      invocationId: String,
    ): QaLeaseResult = QaLeaseResult.Acquired(QaDeviceLease(deviceFingerprint, invocationId))

    override fun release(
      repoRoot: Path,
      lease: QaDeviceLease,
    ): QaLeaseReleaseOutcome = QaLeaseReleaseOutcome.Released(lease.deviceFingerprint)

    override fun releaseAllFor(
      repoRoot: Path,
      invocationId: String,
    ): List<QaLeaseReleaseOutcome> = emptyList()
  }

  private object FakeResourcePort : QaOwnedResourcePort {
    override fun register(
      repoRoot: Path,
      invocationId: String,
      resource: QaOwnedResource,
    ): QaResourceRegistration = QaResourceRegistration.Registered

    override fun release(
      repoRoot: Path,
      invocationId: String,
      resource: QaOwnedResource,
    ): QaResourceReleaseOutcome = QaResourceReleaseOutcome.Released(resource)

    override fun releaseAllFor(
      repoRoot: Path,
      invocationId: String,
    ): List<QaResourceReleaseOutcome> = emptyList()

    override fun reclaimAbandoned(repoRoot: Path): List<QaResourceReleaseOutcome> = emptyList()
  }

  private companion object {
    const val RUN = "qa-run"
  }
}
