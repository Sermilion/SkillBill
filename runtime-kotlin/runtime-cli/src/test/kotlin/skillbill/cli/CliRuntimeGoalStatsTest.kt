package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.infrastructure.sqlite.withLifecycleTelemetryStore
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CliRuntimeGoalStatsTest {
  @Test
  fun `goal-stats --format json emits stable payload`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-goal-stats-json")
    val dbPath = tempDir.resolve("metrics.db")
    seedGoalStatsDb(dbPath)
    val result =
      CliRuntime.run(
        listOf("--db", dbPath.toString(), "goal-stats", "--format", "json"),
        CliRuntimeContext(),
      )
    val payload = decodeJsonObject(result.stdout)
    assertEquals(0, result.exitCode)
    assertEquals("bill-goal-run", payload["workflow"])
    assertEquals(1, payload["total_runs"])
    assertEquals(1.0, (payload["blocked_rate"] as Number).toDouble())
    assertEquals(dbPath.toAbsolutePath().normalize().toString(), payload["db_path"])
    assertEquals(1, (payload["top_blocked_subtasks"] as List<*>).size)
  }

  @Test
  fun `goal-stats human-readable output includes key fields`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-goal-stats-human")
    val dbPath = tempDir.resolve("metrics.db")

    withLifecycleTelemetryStore(tempDir, dbPath) { store ->
      store.goalStarted(
        GoalStartedRecord(
          issueKey = "SKILL-66",
          featureName = "goal telemetry",
          workflowId = "wf-cli-human",
          subtaskTotal = 1,
          resumed = false,
          startedAt = "2026-06-05T10:00:00Z",
          mode = "runtime",
        ),
        level = "full",
      )
      store.goalFinished(
        GoalFinishedRecord(
          issueKey = "SKILL-66",
          workflowId = "wf-cli-human",
          status = "completed",
          startedAt = "2026-06-05T10:00:00Z",
          finishedAt = "2026-06-05T10:30:00Z",
          durationMs = 1_800_000,
          subtasksComplete = 1,
          subtasksBlocked = 0,
          subtasksSkipped = 0,
          mode = "runtime",
        ),
        level = "full",
      )
    }

    val result =
      CliRuntime.run(
        listOf("--db", dbPath.toString(), "goal-stats"),
        CliRuntimeContext(),
      )

    assertEquals(0, result.exitCode)
    assertContains(result.stdout, "total_runs")
    assertContains(result.stdout, "blocked_rate")
  }

  @Test
  fun `goal-stats empty store exits 0 with zero counts`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-goal-stats-empty")
    val dbPath = tempDir.resolve("metrics.db")

    val result =
      CliRuntime.run(
        listOf("--db", dbPath.toString(), "goal-stats", "--format", "json"),
        CliRuntimeContext(),
      )
    val payload = decodeJsonObject(result.stdout)

    assertEquals(0, result.exitCode)
    assertEquals("bill-goal-run", payload["workflow"])
    assertEquals(0, payload["total_runs"])
  }

  @Test
  fun `goal-stats json carries nested trace probe and assessment objects with zeros when nothing was recorded`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-goal-stats-nested")
    val dbPath = tempDir.resolve("metrics.db")

    val result =
      CliRuntime.run(
        listOf("--db", dbPath.toString(), "goal-stats", "--format", "json"),
        CliRuntimeContext(),
      )
    val payload = decodeJsonObject(result.stdout)

    val scenarios = payload["interaction_scenarios"] as Map<*, *>
    val probes = payload["test_probes"] as Map<*, *>
    assertEquals(setOf("traced", "broken", "repaired", "unknown"), scenarios.keys)
    assertEquals(setOf("killed", "survived", "repaired", "not_run"), probes.keys)
    val assessment = payload["goal_assessment"] as Map<*, *>
    assertEquals(0, (assessment["rounds"] as Number).toInt())
    assertEquals(emptyMap<String, Any?>(), assessment["exit_reasons"])
  }
}
