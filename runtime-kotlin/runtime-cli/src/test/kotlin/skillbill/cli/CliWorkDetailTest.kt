package skillbill.cli

import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.create
import skillbill.infrastructure.sqlite.ensureTestDatabase
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.model.EnvironmentContext
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CliWorkDetailTest {
  @Test
  fun `workflow backed plan keeps its invocation and pipeline after board retention expires`() {
    val home = Files.createTempDirectory("cli-work-detail-backed-plan")
    val dbPath = home.resolve("metrics.db")
    val workflowId = seedCompletedPlan(home, dbPath)
    val context = CliRuntimeContext(environment = emptyMap(), userHome = home)
    val board = CliRuntime.run(listOf("--db", dbPath.toString(), "work", "board"), context)
    assertEquals(0, board.exitCode, board.stdout)
    assertEquals(emptyList<Any>(), decodeJsonObject(board.stdout)["items"])

    listOf(workflowId, "plan-invocation").forEach { id ->
      val result = CliRuntime.run(listOf("--db", dbPath.toString(), "work", "detail", id), context)
      assertEquals(0, result.exitCode, result.stdout)
      val item = decodeJsonObject(result.stdout)["item"] as Map<*, *>
      val snapshot = item["snapshot"] as Map<*, *>
      assertEquals("plan-invocation", snapshot["invocation_id"])
      assertEquals("terminal", snapshot["lifecycle_state"])
      assertEquals(listOf("preplan", "plan"), item["step_pipeline"])
      assertEquals("standalone_finished_at", item["finished_at_source"])
    }
  }

  private fun seedCompletedPlan(
    home: Path,
    dbPath: Path,
  ): String {
    val repoRoot = Files.createTempDirectory("cli-work-detail-backed-plan-repo")
    val component =
      RuntimeComponent::class.create(
        RuntimeContext(
          EnvironmentContext(dbPathOverride = dbPath.toString(), environment = emptyMap(), userHome = home),
          TransportContext(),
          WorkflowOpsContext(),
          OptionalCallbacks(),
        ),
      )
    val workflowId =
      assertIs<WorkflowOpenResult.Ok>(
        component.workflowService.open(
          WorkflowServiceOpenArgs(kind = WorkflowFamilyKind.TASK_RUNTIME, issueKey = "SKILL-418"),
        ),
      ).workflowId
    val database = sqliteSessionFactoryForTests(home, dbPath.toString(), emptyMap())
    val finishedAt = Instant.now().minusSeconds(8 * 86_400)
    database.transaction {
      it.standalonePhaseStatuses.register(
        StandalonePhaseStatusRegistration(
          repositoryIdentity = "repo-root-realpath-v1:${repoRoot.toRealPath()}",
          branchCorrelation = "feature/plan",
          issueKey = "SKILL-418",
          workflowId = workflowId,
          invocationId = "plan-invocation",
          phaseId = "plan",
          executionId = "plan-execution",
          lifecycleState = "active",
          currentStep = "plan",
          startedAt = finishedAt.minusSeconds(60),
          leaseOwner = "owner",
          leaseGeneration = 1,
          leaseExpiresAt = Instant.now().plusSeconds(600),
        ),
      )
      it.standalonePhaseStatuses.update(
        StandalonePhaseStatusUpdate(
          executionId = "plan-execution",
          expectedRevision = "1",
          leaseOwner = "owner",
          leaseGeneration = 1,
          lifecycleState = "terminal",
          currentStep = "plan",
          currentActivity = null,
          updatedAt = finishedAt,
          finishedAt = finishedAt,
          terminalResult = "done",
        ),
      )
    }
    return workflowId
  }

  @Test
  fun `detail finds a standalone invocation and returns its phase pipeline`() {
    val home = Files.createTempDirectory("cli-work-detail-home")
    val repoRoot = Files.createTempDirectory("cli-work-detail-repo")
    val dbPath = home.resolve("metrics.db")
    val database = sqliteSessionFactoryForTests(home, dbPath.toString(), emptyMap())
    val now = Instant.now()
    database.transaction {
      it.standalonePhaseStatuses.register(
        StandalonePhaseStatusRegistration(
          repositoryIdentity = "repo-root-realpath-v1:${repoRoot.toRealPath()}",
          branchCorrelation = "feature/detail",
          issueKey = null,
          workflowId = null,
          invocationId = "old-pr-invocation",
          phaseId = "pr",
          executionId = "old-pr-execution",
          lifecycleState = "completed",
          currentStep = "pr",
          startedAt = now.minusSeconds(86_400),
          leaseOwner = "owner",
          leaseGeneration = 1,
          leaseExpiresAt = now.minusSeconds(86_000),
        ),
      )
    }

    val result =
      CliRuntime.run(
        listOf("--db", dbPath.toString(), "work", "detail", "old-pr-invocation", "--format", "json"),
        CliRuntimeContext(environment = emptyMap(), userHome = home),
      )

    assertEquals(0, result.exitCode, result.stdout)
    val item = decodeJsonObject(result.stdout)["item"] as Map<*, *>
    val snapshot = item["snapshot"] as Map<*, *>
    assertEquals("old-pr-invocation", snapshot["invocation_id"])
    assertEquals(listOf("commit_push", "pr", "monitor"), item["step_pipeline"])
  }

  @Test
  fun `unknown id returns a structured problem`() {
    val home = Files.createTempDirectory("cli-work-detail-unknown")
    val dbPath = home.resolve("metrics.db")
    ensureTestDatabase(dbPath).close()

    val result =
      CliRuntime.run(
        listOf("--db", dbPath.toString(), "work", "detail", "missing-workflow"),
        CliRuntimeContext(environment = emptyMap(), userHome = home),
      )

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeJsonObject(result.stdout)
    assertEquals("unknown_work_item", ((payload["problem"] as Map<*, *>)["code"]))
  }

  @Test
  fun `standalone detail pipelines follow the phase id`() {
    val home = Files.createTempDirectory("cli-work-detail-pipelines")
    val repoRoot = Files.createTempDirectory("cli-work-detail-pipelines-repo")
    val dbPath = home.resolve("metrics.db")
    val database = sqliteSessionFactoryForTests(home, dbPath.toString(), emptyMap())
    val phases =
      mapOf(
        "monitor" to listOf("commit_push", "monitor"),
        "plan" to listOf("preplan", "plan"),
        "review" to listOf("review"),
        "validation" to listOf("validation"),
      )
    val now = Instant.now()
    database.transaction {
      phases.keys.forEach { phase ->
        it.standalonePhaseStatuses.register(
          StandalonePhaseStatusRegistration(
            repositoryIdentity = "repo-root-realpath-v1:${repoRoot.toRealPath()}",
            branchCorrelation = "feature/pipeline",
            issueKey = null,
            workflowId = null,
            invocationId = "$phase-invocation",
            phaseId = phase,
            executionId = "$phase-execution",
            lifecycleState = "completed",
            currentStep = phase,
            startedAt = now.minusSeconds(60),
            leaseOwner = phase,
            leaseGeneration = 1,
            leaseExpiresAt = now.plusSeconds(600),
          ),
        )
      }
    }

    phases.forEach { (phase, expectedPipeline) ->
      val result =
        CliRuntime.run(
          listOf("--db", dbPath.toString(), "work", "detail", "$phase-invocation"),
          CliRuntimeContext(environment = emptyMap(), userHome = home),
        )
      assertEquals(0, result.exitCode, result.stdout)
      val item = decodeJsonObject(result.stdout)["item"] as Map<*, *>
      assertEquals(expectedPipeline, item["step_pipeline"])
    }
  }
}
