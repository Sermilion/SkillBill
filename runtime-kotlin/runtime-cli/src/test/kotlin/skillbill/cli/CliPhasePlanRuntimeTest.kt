package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.DECOMPOSITION_MANIFEST_CONTRACT_VERSION
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchModelRequest
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.passThroughResolvedLaunchModel
import skillbill.workflow.taskruntime.model.skeleton.EffectiveLaunchModel
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliPhasePlanRuntimeTest {
  private val tempDir: Path = Files.createTempDirectory("skillbill-cli-phase-plan")
  private val fixture =
    GoalCliFixture(
      tempDir = tempDir,
      dbPath = tempDir.resolve("metrics.db"),
      parentSpec = tempDir.resolve(".feature-specs/$ISSUE_KEY-phase-plan/spec.md"),
      subtaskSpecs = emptyList(),
    )

  @AfterTest
  fun cleanUp() {
    tempDir.toFile().deleteRecursively()
  }

  @Test
  fun `phase plan records one plan workflow and writes a spec bundle that goal preflight accepts`() {
    installGoalBuildPack(tempDir)
    val launcher = PhasePlanLauncher(tempDir)

    val plan =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "phase",
          "plan",
          ISSUE_KEY,
          "split",
          "the",
          "work",
          "--agent",
          "codex",
        ),
        fixture.context(launcher = launcher).copy(repositoryRoot = tempDir),
      )

    assertEquals(0, plan.exitCode, plan.stdout)
    assertContains(plan.stdout, "Manifest: .feature-specs/$ISSUE_KEY-")
    assertEquals(listOf("preplan", "plan"), launcher.phaseIds)
    assertEquals(1, rowCount("feature_task_workflows"))
    assertEquals(1, rowCount("goal_shared_preplans"))
    assertEquals(2, rowCount("goal_subtask_plans"))
    val preflight =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "goal",
          "preflight",
          ISSUE_KEY,
          "--agent",
          "codex",
          "--repo-root",
          tempDir.toString(),
          "--format",
          "json",
        ),
        fixture.context(launcher = launcher),
      )
    assertEquals(0, preflight.exitCode, preflight.stdout)
    val payload =
      requireNotNull(
        JsonCodec.anyToStringAnyMap(
          JsonCodec.jsonElementToValue(requireNotNull(JsonCodec.parseObjectOrNull(preflight.stdout))),
        ),
      ) { "Expected preflight JSON object but got: ${preflight.stdout}" }
    assertEquals(ISSUE_KEY, payload["issue_key"])
    assertEquals("new_work", payload["verdict"], preflight.stdout)
    assertEquals(false, payload["manifest_missing"], preflight.stdout)
    assertEquals(1, rowCount("feature_task_workflows"))
    assertGoalStartsAtImplementation(launcher)
  }

  private fun assertGoalStartsAtImplementation(launcher: PhasePlanLauncher) {
    val implementation = GoalFixtureAgentRunLauncher(fixture, noTerminalSubtask = 1)
    val goal =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "goal",
          ISSUE_KEY,
          "--agent",
          "codex",
          "--repo-root",
          tempDir.toString(),
        ),
        fixture.context(launcher = implementation),
      )
    assertEquals(3, goal.exitCode, goal.stdout + goal.stderr)
    assertEquals(1, implementation.requests.size)
    assertTrue(implementation.requests.single().skillRunRequest.goalContinuation != null)
    val workflowId = goal.payload?.get("workflow_id")?.toString().orEmpty()
    val child = RuntimeWorkflowTestSupport.get(fixture.dbPath, workflowId, fixture.context(launcher = implementation))
    assertEquals("implement", child["current_step_id"])
    DriverManager.getConnection("jdbc:sqlite:${fixture.dbPath}").use { connection ->
      connection.createStatement().use { it.executeUpdate("DELETE FROM goal_subtask_plans WHERE subtask_id = 2") }
    }
    val settled =
      CliRuntime.run(
        listOf("--db", fixture.dbPath.toString(), "phase", "plan", ISSUE_KEY, "--agent", "codex"),
        fixture.context(launcher = launcher).copy(repositoryRoot = tempDir),
      )
    assertEquals(0, settled.exitCode, settled.stdout + settled.stderr)
    assertEquals(listOf("preplan", "plan"), launcher.phaseIds)
    assertEquals(2, rowCount("goal_subtask_plans"))
  }

  private fun rowCount(table: String): Int =
    DriverManager.getConnection("jdbc:sqlite:${fixture.dbPath}").use { connection ->
      val exists =
        connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { query ->
          query.setString(1, table)
          query.executeQuery().use { rows -> rows.next() }
        }
      if (!exists) return@use 0
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
          assertTrue(rows.next())
          rows.getInt(1)
        }
      }
    }

  private class PhasePlanLauncher(
    private val repoRoot: Path,
  ) : AgentRunLauncher {
    val phaseIds = mutableListOf<String>()

    override fun resolveLaunchModel(request: AgentRunLaunchModelRequest): EffectiveLaunchModel =
      passThroughResolvedLaunchModel(request)

    override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
      val phaseId =
        requireNotNull(PHASE_LINE.find(request.skillRunRequest.promptOverride.orEmpty())?.groupValues?.get(1)) {
          "phase plan launched a prompt with no phase header"
        }
      phaseIds += phaseId
      val stdout = if (phaseId == "plan") authorBundle() else phasePlanningPayload(phaseId)
      return agentRunLaunchFacts(agent = SupportedAgent.CODEX, stdout = stdout, stderr = "")
    }

    private fun authorBundle(): String {
      val bundle = seededBundle()
      Files.writeString(bundle.resolve("spec.md"), specText("Parent", "The split work completes."))
      SUBTASK_FILES.forEach { (id, fileName) ->
        Files.writeString(bundle.resolve(fileName), specText("Subtask $id", "Subtask $id works."))
      }
      Files.writeString(
        bundle.resolve("decomposition-manifest.yaml"),
        manifestYaml(repoRoot.relativize(bundle).toString(), bundle.fileName.toString().removePrefix("$ISSUE_KEY-")),
      )
      return "Split the work into two ordered subtasks."
    }

    private fun seededBundle(): Path =
      Files.list(repoRoot.resolve(".feature-specs")).use { directories ->
        directories.toList().single { directory -> directory.fileName.toString().startsWith("$ISSUE_KEY-") }
      }

    private fun specText(
      title: String,
      criterion: String,
    ): String =
      "# $title\n\n## Acceptance Criteria\n\n1. $criterion\n" +
        "\n## Implementation Details\nImplement $criterion in the owning production path.\n"
  }

  private companion object {
    const val ISSUE_KEY = "SKILL-904"
    val PHASE_LINE = Regex("""Phase: (\w+) \(""")
    val SUBTASK_FILES = mapOf(1 to "spec_subtask_1_first-part.md", 2 to "spec_subtask_2_second-part.md")

    fun manifestYaml(
      bundleDirectory: String,
      featureName: String,
    ): String =
      """
      ---
      contract_version: "$DECOMPOSITION_MANIFEST_CONTRACT_VERSION"
      issue_key: "$ISSUE_KEY"
      feature_name: "$featureName"
      parent_spec_path: "$bundleDirectory/spec.md"
      status: "pending"
      execution_model: "same_branch_commit_per_subtask"
      base_branch: "main"
      feature_branch: "feat/$ISSUE_KEY-$featureName"
      stack_branches: []
      current_subtask_intent:
        subtask_id: 1
        action: "start"
      subtasks:
      - id: 1
        name: "first part"
        spec_path: "$bundleDirectory/spec_subtask_1_first-part.md"
        status: "pending"
        branch: null
        commit_sha: null
        workflow_id: null
        blocked_reason: null
        last_resumable_step: null
        linear_issue_id: null
        finalizing_agent_id: null
        participating_agent_ids: []
        dependencies: []
      - id: 2
        name: "second part"
        spec_path: "$bundleDirectory/spec_subtask_2_second-part.md"
        status: "pending"
        branch: null
        commit_sha: null
        workflow_id: null
        blocked_reason: null
        last_resumable_step: null
        linear_issue_id: null
        finalizing_agent_id: null
        participating_agent_ids: []
        dependencies:
        - subtask_id: 1
          optional: false
          skipped: false
      """.trimIndent() + "\n"
  }
}
