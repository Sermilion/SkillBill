package skillbill.engine.work

import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.infrastructure.sqlite.idestatus.SessionAgentBuildRunRepository
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.AgentBuildRunRepository
import skillbill.ports.idestatus.model.AgentBuildOutcome
import skillbill.ports.idestatus.model.AgentBuildRunStart
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.idestatus.model.WorkBuildState
import skillbill.ports.idestatus.model.WorkDetailRequest
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkBuildReadSurfaceTest {
  @Test
  fun `board and detail overlay the parent goal build with history from SQLite`() {
    val startedAt = ideStatusObservedAt.minusSeconds(30)
    val repositoryIdentity = "repo-root-realpath-v1:/repo"
    val buildDatabase =
      sqliteDatabaseSessionFactory(userHome = Files.createTempDirectory("work-build-surface"), environment = emptyMap())
    val buildStore = SessionAgentBuildRunRepository(buildDatabase)
    buildDatabase.transaction { unitOfWork ->
      listOf(
        buildStart("old-100", repositoryIdentity, startedAt.minusSeconds(1_000), 11) to 100L,
        buildStart("old-200", repositoryIdentity, startedAt.minusSeconds(800), 12) to 200L,
        buildStart("old-400", repositoryIdentity, startedAt.minusSeconds(500), 13) to 400L,
      ).forEach { (row, durationSeconds) ->
        unitOfWork.agentBuildRuns.start(row)
        unitOfWork.agentBuildRuns.settle(
          row.buildId,
          row.startedAt.plusSeconds(durationSeconds),
          AgentBuildOutcome.PASSED,
          0,
        )
      }
      unitOfWork.agentBuildRuns.start(
        buildStart("current", repositoryIdentity, startedAt, 14).copy(runId = "w-child", parentRunId = "goal-1"),
      )
    }
    val fixture = gitRepoFixture("work-build-repo")
    val identity = testGoalRepositoryIdentity(fixture)
    val service =
      ideStatusService(
        goalWithLaunchedChildDatabase(identity, startedAt),
        manifestStore =
          StubGoalManifestStore(
            goalManifestState(fixture, identity, childWorkflowId = "w-child"),
            lease = lease(ideStatusObservedAt.minusSeconds(5)),
          ),
        ports = IdeStatusServiceTestPorts(buildRunRepository = buildStore),
      )

    val request = WorkBoardRequest(observedAt = ideStatusObservedAt)
    val boardItem =
      service.board(request).items.single {
        it.snapshot.workflowId == "goal-1"
      }
    val selectedBoardItem =
      service.projectBoardSelection(listOf("goal-1"), request).items.single {
        it.snapshot.workflowId == "goal-1"
      }
    val detailItem = requireNotNull(service.detail(WorkDetailRequest("goal-1", observedAt = ideStatusObservedAt)).item)
    val boardBuild = requireNotNull(boardItem.build)

    assertEquals(WorkBuildState.RUNNING, boardBuild.state)
    assertEquals(30_000L, boardBuild.elapsedMs)
    assertEquals(ideStatusObservedAt, boardBuild.elapsedAsOf)
    assertEquals(233_333L, boardBuild.usualMs)
    assertEquals(3, boardBuild.sampleCount)
    assertEquals(WorkBuildState.PASSED, boardBuild.previous?.state)
    assertEquals(400_000L, boardBuild.previous?.durationMs)
    assertEquals(startedAt.minusSeconds(100), boardBuild.previous?.finishedAt)
    assertEquals(boardBuild, selectedBoardItem.build)
    assertEquals(boardBuild, detailItem.build)
  }

  @Test
  fun `failed retry replaces an abandoned child build on board selection and detail`() {
    val startedAt = ideStatusObservedAt.minusSeconds(30)
    val finishedAt = startedAt.plusSeconds(12)
    val repositoryIdentity = "repo-root-realpath-v1:/repo"
    val buildDatabase =
      sqliteDatabaseSessionFactory(userHome = Files.createTempDirectory("work-build-retry"), environment = emptyMap())
    val buildStore = SessionAgentBuildRunRepository(buildDatabase)
    buildDatabase.transaction { unitOfWork ->
      unitOfWork.agentBuildRuns.start(
        buildStart("abandoned", repositoryIdentity, startedAt.minusSeconds(10), 13)
          .copy(runId = "w-child", parentRunId = "goal-1"),
      )
      unitOfWork.agentBuildRuns.abandonOpen("w-child")
      val retry =
        buildStart("retry", repositoryIdentity, startedAt, 14).copy(runId = "w-child", parentRunId = "goal-1")
      unitOfWork.agentBuildRuns.start(retry)
      unitOfWork.agentBuildRuns.settle("retry", finishedAt, AgentBuildOutcome.FAILED, 17)
    }
    val fixture = gitRepoFixture("work-build-retry-repo")
    val identity = testGoalRepositoryIdentity(fixture)
    val service =
      ideStatusService(
        goalWithLaunchedChildDatabase(identity, startedAt),
        manifestStore =
          StubGoalManifestStore(
            goalManifestState(fixture, identity, childWorkflowId = "w-child"),
            lease = lease(ideStatusObservedAt.minusSeconds(5)),
          ),
        ports = IdeStatusServiceTestPorts(buildRunRepository = buildStore),
      )

    val request = WorkBoardRequest(observedAt = ideStatusObservedAt)
    val boardItem = service.board(request).items.single { it.snapshot.workflowId == "goal-1" }
    val selectedBoardItem =
      service.projectBoardSelection(listOf("goal-1"), request).items.single { it.snapshot.workflowId == "goal-1" }
    val detailItem = requireNotNull(service.detail(WorkDetailRequest("goal-1", observedAt = ideStatusObservedAt)).item)
    val boardBuild = requireNotNull(boardItem.build)

    assertEquals(WorkBuildState.FAILED, boardBuild.state)
    assertEquals("./gradlew check", boardBuild.command)
    assertEquals(17, boardBuild.exitCode)
    assertEquals(finishedAt, boardBuild.finishedAt)
    assertEquals(boardBuild, selectedBoardItem.build)
    assertEquals(boardBuild, detailItem.build)
  }

  @Test
  fun `open build on a finished standalone validation stays visible on board and detail`() {
    val database = standaloneBuildDatabase()
    val service = ideStatusService(database)
    val board = service.board(WorkBoardRequest(observedAt = ideStatusObservedAt)).items.single()
    val detail =
      assertNotNull(service.detail(WorkDetailRequest("validation-run", observedAt = ideStatusObservedAt)).item)
    val build = assertNotNull(board.build)

    assertFalse(board.running)
    assertFalse(detail.running)
    assertEquals(WorkBuildState.FINISHED, build.state)
    assertNull(build.elapsedMs)
    assertNull(build.elapsedAsOf)
    assertNull(build.exitCode)
    assertEquals(build, detail.build)
  }

  @Test
  fun `build store failure leaves a standalone board item readable without its build`() {
    val database = standaloneBuildDatabase()
    val failingStore =
      object : AgentBuildRunRepository by SessionAgentBuildRunRepository(database) {
        override fun latestForRun(runIds: Set<String>) = error("store unavailable")
      }
    val board =
      ideStatusService(database, ports = IdeStatusServiceTestPorts(buildRunRepository = failingStore))
        .board(WorkBoardRequest(observedAt = ideStatusObservedAt))

    assertEquals("validation-run", board.items.single().snapshot.execution?.invocationId)
    assertNull(board.items.single().build)
  }

  private fun standaloneBuildDatabase(): DatabaseSessionFactory {
    val database =
      sqliteDatabaseSessionFactory(
        userHome = Files.createTempDirectory("work-build-standalone"),
        environment = emptyMap(),
        clock = ideStatusClock,
      )
    val repositoryIdentity = testGoalRepositoryIdentity(gitRepoFixture("work-build-standalone-repo"))
    database.transaction { unit ->
      val record =
        unit.standalonePhaseStatuses.register(
          StandalonePhaseStatusRegistration(
            repositoryIdentity = repositoryIdentity,
            branchCorrelation = "main",
            issueKey = null,
            workflowId = null,
            invocationId = "validation-run",
            phaseId = "validation",
            executionId = "validation-execution",
            lifecycleState = "active",
            currentStep = "validation",
            startedAt = ideStatusObservedAt.minusSeconds(60),
            leaseOwner = "owner",
            leaseGeneration = 1,
            leaseExpiresAt = ideStatusObservedAt.plusSeconds(30),
          ),
        )
      assertEquals(
        StandalonePhaseStatusUpdateResult.ACCEPTED,
        unit.standalonePhaseStatuses.update(
          StandalonePhaseStatusUpdate(
            executionId = record.executionId,
            expectedRevision = record.statusRevision,
            leaseOwner = record.leaseOwner,
            leaseGeneration = record.leaseGeneration,
            lifecycleState = "terminal",
            currentStep = "validation",
            currentActivity = null,
            updatedAt = ideStatusObservedAt.minusSeconds(1),
            finishedAt = ideStatusObservedAt.minusSeconds(1),
            terminalResult = "passed",
          ),
        ),
      )
      unit.agentBuildRuns.start(
        buildStart("open", repositoryIdentity, ideStatusObservedAt.minusSeconds(30), 12).copy(runId = "validation-run"),
      )
    }
    return database
  }

  private fun lease(heartbeatAt: Instant) =
    GoalRunnerExecutionLease(
      generation = 1,
      ownerToken = "owner-token",
      hostIdentity = "test-host",
      bootIdentity = "boot-id",
      pid = 4321,
      processBirthToken = "birth-token",
      heartbeatAt = heartbeatAt.toString(),
      expiresAt = heartbeatAt.plusSeconds(30).toString(),
    )

  private fun buildStart(
    buildId: String,
    repositoryIdentity: String,
    startedAt: Instant,
    pid: Long,
  ) = AgentBuildRunStart(
    buildId = buildId,
    runId = buildId,
    parentRunId = null,
    repositoryIdentity = repositoryIdentity,
    command = "./gradlew check",
    shortCommand = "check",
    tool = "gradle",
    projectKind = "gradle",
    pid = pid,
    startedAt = startedAt,
  )
}
