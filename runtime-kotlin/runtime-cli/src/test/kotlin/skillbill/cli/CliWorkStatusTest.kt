package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.workflow.identity.status.IdeStatusPayloadKeys
import skillbill.engine.goalrunner.status.completed
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.infrastructure.contracts.workflow.goal.IdeStatusSchemaValidator
import skillbill.infrastructure.sqlite.ensureTestDatabase
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.idestatus.model.IdeStatusFreshness
import skillbill.ports.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.idestatus.model.IdeStatusPlanning
import skillbill.ports.idestatus.model.IdeStatusProgress
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusStep
import skillbill.ports.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliWorkStatusTest {
  @Test
  fun `work status emits schema-valid idle json for a git repo with empty database`() {
    val fixture = gitRepoFixture("skillbill-cli-work-status-idle")
    val dbPath = fixture.resolve("metrics.db")
    ensureTestDatabase(dbPath).close()

    val result =
      CliRuntime.run(
        listOf("--db", dbPath.toString(), "work", "status", "--repo-root", fixture.toString(), "--format", "json"),
        context =
          CliRuntimeContext(
            environment = emptyMap(),
            userHome = Files.createTempDirectory("skillbill-cli-work-status-home"),
          ),
      )

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeJsonObject(result.stdout)
    assertEquals("idle", payload["lifecycle_state"])
    assertEquals("no_matching_work", (payload["problem"] as Map<*, *>)["code"])
    IdeStatusSchemaValidator.validate(payload, "cli-idle")
    assertFalse(result.stdout.contains("Exception"))
    assertFalse(result.stdout.contains("at skillbill"))
  }

  @Test
  fun `work status reports absent database as schema-valid json`() {
    val fixture = gitRepoFixture("skillbill-cli-work-status-absent-db")
    val missingDb = fixture.resolve("missing-metrics.db")

    val result =
      CliRuntime.run(
        listOf("--db", missingDb.toString(), "work", "status", "--repo-root", fixture.toString(), "--format", "json"),
        context =
          CliRuntimeContext(
            environment = emptyMap(),
            userHome = Files.createTempDirectory("skillbill-cli-work-status-home-absent"),
          ),
      )

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeJsonObject(result.stdout)
    assertEquals("absent_database", (payload["problem"] as Map<*, *>)["code"])
    IdeStatusSchemaValidator.validate(payload, "cli-absent-db")
  }

  @Test
  fun `work status rejects invalid repository root with typed problem json`() {
    val missing = Files.createTempDirectory("skillbill-cli-work-status-missing").resolve("no-such-repo")
    val dbPath = Files.createTempDirectory("skillbill-cli-work-status-invalid-db").resolve("metrics.db")
    ensureTestDatabase(dbPath).close()

    val result =
      CliRuntime.run(
        listOf("--db", dbPath.toString(), "work", "status", "--repo-root", missing.toString(), "--format", "json"),
      )

    assertEquals(1, result.exitCode, result.stdout)
    val payload = decodeJsonObject(result.stdout)
    assertEquals("invalid_repository_input", (payload["problem"] as Map<*, *>)["code"])
    IdeStatusSchemaValidator.validate(payload, "cli-invalid-root")
    assertFalse(result.stdout.contains("\tat "))
  }

  @Test
  fun `work status performs no database writes`() {
    val fixture = gitRepoFixture("skillbill-cli-work-status-readonly")
    val dbPath = fixture.resolve("metrics.db")
    ensureTestDatabase(dbPath).close()
    val before = Files.readAllBytes(dbPath)

    val result =
      CliRuntime.run(
        listOf("--db", dbPath.toString(), "work", "status", "--repo-root", fixture.toString(), "--format", "json"),
        context =
          CliRuntimeContext(
            environment = emptyMap(),
            userHome = Files.createTempDirectory("skillbill-cli-work-status-home-ro"),
          ),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertTrue(before.contentEquals(Files.readAllBytes(dbPath)))
  }

  @Test
  fun `work status projects expired phases without writing to the database`() {
    val fixture = Files.createTempDirectory("skillbill-cli-work-status-expired")
    val git =
      ProcessBuilder("git", "init", "--initial-branch=feat/status-readonly", fixture.toString())
        .redirectErrorStream(true).start()
    val gitOutput = git.inputStream.bufferedReader().use { it.readText() }
    assertEquals(0, git.waitFor(), gitOutput)
    val dbPath = fixture.resolve("metrics.db")
    val startedAt = Instant.now().minusSeconds(120)
    val database =
      sqliteSessionFactoryForTests(
        userHome = fixture,
        dbPathOverride = dbPath.toString(),
        environment = emptyMap(),
      )
    database.transaction {
      it.standalonePhaseStatuses.register(
        StandalonePhaseStatusRegistration(
          repositoryIdentity = "repo-root-realpath-v1:${fixture.toRealPath()}",
          branchCorrelation = "feat/status-readonly",
          issueKey = null,
          workflowId = null,
          invocationId = "expired-pr-invocation",
          phaseId = "pr",
          executionId = "expired-pr-execution",
          lifecycleState = "active",
          currentStep = "pr",
          startedAt = startedAt,
          leaseOwner = "expired-pr-owner",
          leaseGeneration = 1,
          leaseExpiresAt = startedAt.plusSeconds(60),
        ),
      )
    }
    val before = Files.readAllBytes(dbPath)
    repeat(2) {
      val result =
        CliRuntime.run(
          listOf("--db", dbPath.toString(), "work", "status", "--repo-root", fixture.toString(), "--format", "json"),
          context = CliRuntimeContext(environment = emptyMap(), userHome = fixture),
        )
      assertEquals(0, result.exitCode, result.stdout)
      val payload = decodeJsonObject(result.stdout)
      IdeStatusSchemaValidator.validate(payload, "cli-expired-phase")
      assertEquals("paused", payload["lifecycle_state"])
      assertEquals("runner_interrupted", (payload["current_step"] as Map<*, *>)["id"])
      assertEquals("1", payload[IdeStatusPayloadKeys.STATUS_REVISION])
      assertTrue(before.contentEquals(Files.readAllBytes(dbPath)))
    }
    val stored =
      database.read {
        it.standalonePhaseStatuses.readEligible(
          "repo-root-realpath-v1:${fixture.toRealPath()}",
          "feat/status-readonly",
          startedAt.plusSeconds(120),
        ).single()
      }
    assertEquals("active", stored.lifecycleState)
    assertEquals("pr", stored.currentStep)
    assertEquals("1", stored.statusRevision)
  }

  @Test
  fun `mid-planning goal emit shape validates against the canonical schema`() {
    val snapshot =
      IdeStatusSnapshot(
        repositoryIdentity = "repo-root-realpath-v1:/repo",
        issueKey = "SKILL-165",
        workflowId = "goal-1",
        workflowFamily = IdeStatusWorkflowFamily.FEATURE_GOAL,
        lifecycleState = IdeStatusLifecycleState.ACTIVE,
        currentStep = IdeStatusStep(id = "planning", label = "Planning"),
        progress = IdeStatusProgress(completed = 0, total = 5),
        planning =
          IdeStatusPlanning(
            state = GoalPlanningStatusState.PARTIALLY_PLANNED,
            sharedPreplanPrepared = true,
            plannedSubtaskCount = 2,
            totalSubtaskCount = 5,
            currentPlanningSubtaskId = "3",
            planningWaveSubtaskIds = listOf("3", "4", "5"),
            reason = "Planning subtask 3.",
          ),
        updatedAt = Instant.parse("2026-08-06T10:00:00Z"),
        freshness = IdeStatusFreshness.FRESH,
        summary = "Goal SKILL-165 is planning subtasks (2/5 planned). 3 subtasks are being planned now.",
      )

    val wire = IdeStatusSchemaValidator.wireMap(snapshot)
    IdeStatusSchemaValidator.validate(wire, "cli-goal-planning")
    val planning = wire["planning"] as Map<*, *>
    assertEquals(listOf("3", "4", "5"), planning["planning_wave_subtask_ids"])
  }

  @Test
  fun `work status requires repo-root`() {
    val result = CliRuntime.run(listOf("work", "status", "--format", "json"))
    assertTrue(result.exitCode != 0, result.stdout)
  }

  private fun gitRepoFixture(prefix: String): Path {
    val root = Files.createTempDirectory(prefix)
    Files.createDirectory(root.resolve(".git"))
    return root
  }
}
