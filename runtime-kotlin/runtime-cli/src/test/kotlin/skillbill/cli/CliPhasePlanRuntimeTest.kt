package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.DECOMPOSITION_MANIFEST_CONTRACT_VERSION
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
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
  fun `phase plan writes a spec bundle that goal preflight accepts with no workflow or session rows`() {
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
    assertEquals(0, rowCount("feature_task_workflows"))
    assertEquals(0, rowCount("feature_task_runtime_sessions"))
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
      val bundle = repoRoot.resolve(BUNDLE_DIRECTORY)
      Files.createDirectories(bundle)
      Files.writeString(bundle.resolve("spec.md"), specText("Parent", "The split work completes."))
      SUBTASK_FILES.forEach { (id, fileName) ->
        Files.writeString(bundle.resolve(fileName), specText("Subtask $id", "Subtask $id works."))
      }
      Files.writeString(bundle.resolve("decomposition-manifest.yaml"), MANIFEST_YAML)
      return "Split the work into two ordered subtasks."
    }

    private fun specText(
      title: String,
      criterion: String,
    ): String = "# $title\n\n## Acceptance Criteria\n\n1. $criterion\n"
  }

  private companion object {
    const val ISSUE_KEY = "SKILL-904"
    val PHASE_LINE = Regex("""Phase: (\w+) \(""")
    const val BUNDLE_DIRECTORY = ".feature-specs/$ISSUE_KEY-phase-plan"
    val SUBTASK_FILES = mapOf(1 to "spec_subtask_1_first-part.md", 2 to "spec_subtask_2_second-part.md")
    val MANIFEST_YAML =
      """
      ---
      contract_version: "$DECOMPOSITION_MANIFEST_CONTRACT_VERSION"
      issue_key: "$ISSUE_KEY"
      feature_name: "phase-plan"
      parent_spec_path: "$BUNDLE_DIRECTORY/spec.md"
      status: "pending"
      execution_model: "same_branch_commit_per_subtask"
      base_branch: "main"
      feature_branch: "feat/$ISSUE_KEY-phase-plan"
      stack_branches: []
      current_subtask_intent:
        subtask_id: 1
        action: "start"
      subtasks:
      - id: 1
        name: "first part"
        spec_path: "$BUNDLE_DIRECTORY/spec_subtask_1_first-part.md"
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
        spec_path: "$BUNDLE_DIRECTORY/spec_subtask_2_second-part.md"
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
