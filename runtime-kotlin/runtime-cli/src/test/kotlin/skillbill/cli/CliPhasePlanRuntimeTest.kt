package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
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
    val launcher = PhasePlanLauncher()

    val plan =
      CliRuntime.run(
        listOf("--db", fixture.dbPath.toString(), "phase", "plan", ISSUE_KEY, "split", "the", "work", "--agent", "codex"),
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

  /** Rows in [table]; a table the run never created holds none. */
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

  private class PhasePlanLauncher : AgentRunLauncher {
    val phaseIds = mutableListOf<String>()

    override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
      val phaseId =
        requireNotNull(PHASE_LINE.find(request.skillRunRequest.promptOverride.orEmpty())?.groupValues?.get(1)) {
          "phase plan launched a prompt with no phase header"
        }
      phaseIds += phaseId
      val stdout = if (phaseId == "plan") DECOMPOSE_PLAN_OUTPUT else phasePlanningPayload(phaseId)
      return agentRunLaunchFacts(agent = SupportedAgent.CODEX, stdout = stdout, stderr = "")
    }
  }

  private companion object {
    const val ISSUE_KEY = "SKILL-904"
    val PHASE_LINE = Regex("""Phase: (\w+) \(""")
    val DECOMPOSE_PLAN_OUTPUT =
      """
      {
        "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
        "phase_id": "plan",
        "status": "completed",
        "summary": "The work splits into ordered subtasks.",
        "produced_outputs": {
          "value": "Split the work into two ordered subtasks.",
          "decomposition_package": {
            "mode": "decompose",
            "reason": "The work splits into ordered subtasks.",
            "feature_name": "phase plan",
            "parent_spec_overview": "Split the work into two ordered subtasks.",
            "validation_strategy": "bill-code-check",
            "base_branch": "main",
            "feature_branch": "feat/$ISSUE_KEY-phase-plan",
            "subtasks": [
              {
                "id": 1,
                "name": "first part",
                "scope": "Deliver the first part.",
                "acceptance_criteria": ["The first part works."],
                "non_goals": [],
                "dependency_notes": "First subtask.",
                "validation_strategy": "unit tests",
                "next_path": "Work subtask 2 next.",
                "depends_on": []
              },
              {
                "id": 2,
                "name": "second part",
                "scope": "Deliver the second part.",
                "acceptance_criteria": ["The second part works."],
                "non_goals": [],
                "dependency_notes": "Depends on subtask 1.",
                "validation_strategy": "unit tests",
                "next_path": "Return to the goal.",
                "depends_on": [1]
              }
            ]
          }
        }
      }
      """.trimIndent()
  }
}
