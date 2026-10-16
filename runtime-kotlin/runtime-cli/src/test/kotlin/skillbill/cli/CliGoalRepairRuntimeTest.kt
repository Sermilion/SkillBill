package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.persist.durationMillis
import skillbill.infrastructure.sqlite.ensureTestDatabase
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.phaseRecordsFromWorkflowArtifacts
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.label()

class CliGoalRepairRuntimeTest {
  @Test
  fun `goal repair help enumerates wedge classes and does-not-touch statement`() {
    val result =
      CliRuntime.run(
        listOf("goal", "repair", "--help"),
        CliRuntimeContext(environment = emptyMap(), executableLookup = ExecutableLookup { true }),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "validation_depth")
    assertContains(result.stdout, "review_base_sha")
    assertContains(result.stdout, "remediation_base_sha")
    assertContains(result.stdout, "goal_continuation_outcome")
    assertContains(result.stdout, "completed upstream")
    assertContains(result.stdout, "stale_execution_lease")
    assertContains(result.stdout, "stale_child_worker_lease")
    assertContains(result.stdout, "stale_runner_interrupted_pause")

    val help = result.stdout.replace(Regex("\\s+"), " ")
    assertContains(help, "Does not touch")
    assertContains(help, "completed commit shas")
    assertContains(help, "review pass history")
    assertContains(help, "audit repair state")
    assertContains(result.stdout, "--apply")
  }

  @Test
  fun `goal repair inspect-only on a healthy fixture exits zero and writes nothing`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))
    val before = readAllRuntimeArtifactsJson(fixture)

    val result =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "goal",
          "repair",
          "SKILL-901",
          "--repo-root",
          fixture.tempDir.toString(),
        ),
        fixture.context(launcher = NoopGoalTestAgentRunLauncher),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: healthy")
    assertEquals(before, readAllRuntimeArtifactsJson(fixture))
  }

  @Test
  fun `goal repair unknown issue exits non-zero as not_found`() {
    val fixture = goalFixture(subtaskCount = 1)
    val result =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "goal",
          "repair",
          "SKILL-404",
          "--repo-root",
          fixture.tempDir.toString(),
        ),
        fixture.context(launcher = NoopGoalTestAgentRunLauncher),
      )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: not_found")
  }

  @Test
  fun `goal repair inspect reports missing validation_depth without applying`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))
    val childWorkflowId = resolveCompletedChildWorkflowId(fixture)
    stripValidationDepth(fixture, childWorkflowId)
    val before = readAllRuntimeArtifactsJson(fixture)

    val result =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "goal",
          "repair",
          "SKILL-901",
          "--repo-root",
          fixture.tempDir.toString(),
        ),
        fixture.context(launcher = NoopGoalTestAgentRunLauncher),
      )

    assertEquals(2, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: inspected")
    assertContains(result.stdout, "missing_validation_depth")
    assertContains(result.stdout, "validation_depth")
    assertFalse(result.stdout.contains("applied_repairs:"))
    assertEquals(before, readAllRuntimeArtifactsJson(fixture))
  }

  @Test
  fun `goal repair apply clears missing validation_depth and records evidence`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))
    val childWorkflowId = resolveCompletedChildWorkflowId(fixture)
    stripValidationDepth(fixture, childWorkflowId)

    val result =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "goal",
          "repair",
          "SKILL-901",
          "--repo-root",
          fixture.tempDir.toString(),
          "--apply",
        ),
        fixture.context(launcher = NoopGoalTestAgentRunLauncher),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: repaired")
    assertContains(result.stdout, "applied_repairs:")
    assertContains(result.stdout, "validation_depth")
    val artifacts = readChildArtifacts(fixture, childWorkflowId)
    assertTrue(artifacts.contains("\"validation_depth\""))
    assertTrue(artifacts.contains("goal_child_repair_evidence"))
  }

  @Test
  fun `goal repair inspect reports stale lease and pause wedges seeded like issue 342`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))
    val childWorkflowId = resolveCompletedChildWorkflowId(fixture)
    seedIssue342StaleParentControlState(fixture)
    seedExpiredWorkerLease(fixture, childWorkflowId)

    val result =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "goal",
          "repair",
          "SKILL-901",
          "--repo-root",
          fixture.tempDir.toString(),
        ),
        fixture.context(launcher = NoopGoalTestAgentRunLauncher),
      )

    assertEquals(2, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: inspected")
    assertContains(result.stdout, "stale_execution_lease")
    assertContains(result.stdout, "stale_child_worker_lease")
    assertContains(result.stdout, "stale_runner_interrupted_pause")
  }

  @Test
  fun `goal repair apply clears stale lease and pause wedges seeded like issue 342`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))
    val childWorkflowId = resolveCompletedChildWorkflowId(fixture)
    seedIssue342StaleParentControlState(fixture)
    seedExpiredWorkerLease(fixture, childWorkflowId)

    val result =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "goal",
          "repair",
          "SKILL-901",
          "--repo-root",
          fixture.tempDir.toString(),
          "--apply",
        ),
        fixture.context(launcher = NoopGoalTestAgentRunLauncher),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: repaired")
    assertEquals(0, workerLeaseRowCount(fixture, childWorkflowId))
    val controlState =
      JsonCodec.anyToStringAnyMap(
        JsonCodec.jsonElementToValue(requireNotNull(JsonCodec.parseObjectOrNull(parentControlStateJson(fixture)))),
      ).orEmpty()
    assertNull(controlState["execution_lease"])
    assertFalse(controlState["pause_reason"] == "runner_interrupted")
    assertEquals(false, controlState["paused"])
  }

  @Test
  fun `goal repair apply reopens completed upstream output wedge through sqlite`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))
    val childWorkflowId = resolveCompletedChildWorkflowId(fixture)
    seedCompletedUpstreamMissingOutput(fixture, childWorkflowId)

    val result =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "goal",
          "repair",
          "SKILL-901",
          "--repo-root",
          fixture.tempDir.toString(),
          "--apply",
        ),
        fixture.context(launcher = NoopGoalTestAgentRunLauncher),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "status: repaired")
    assertContains(result.stdout, "completed_upstream_missing_output")
    val repairedRecords =
      phaseRecordsFromWorkflowArtifacts(parseWorkflowArtifactsForTest(readChildArtifacts(fixture, childWorkflowId)))
    assertEquals(WorkflowStepStatus.PENDING, repairedRecords.getValue("verify_findings").status)
    assertEquals(WorkflowStepStatus.PENDING, repairedRecords.getValue("implement_fix").status)
  }

  @Test
  fun `goal resume clears stale runner state before it runs and prints each cleared wedge`() {
    val fixture = goalFixture(subtaskCount = 2)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    val paused =
      CliRuntime.run(
        fixture.goalCommand(extra = listOf("--stop-after-subtask", "1")),
        fixture.context(launcher = launcher),
      )
    assertEquals(2, paused.exitCode, paused.stdout)
    val childWorkflowId = resolveCompletedChildWorkflowId(fixture)
    seedIssue342StaleParentControlState(fixture)
    seedExpiredWorkerLease(fixture, childWorkflowId)
    val live = StringBuilder()

    val resumed =
      CliRuntime.run(
        fixture.goalCommand(),
        fixture.context(launcher = launcher, liveStdout = { live.append(it) }),
      )

    val output = live.toString()
    assertContains(output, "goal SKILL-901: cleared stale_execution_lease on the parent goal")
    assertContains(output, "goal SKILL-901: cleared stale_runner_interrupted_pause on the parent goal")
    assertContains(output, "goal SKILL-901: cleared stale_child_worker_lease on subtask 1")
    assertEquals(0, workerLeaseRowCount(fixture, childWorkflowId))
    assertFalse(resumed.stdout.contains("runner_interrupted"), resumed.stdout)
  }

  private fun resolveCompletedChildWorkflowId(fixture: GoalCliFixture): String =
    ensureTestDatabase(fixture.dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery(
          "SELECT workflow_id FROM feature_task_workflows " +
            "WHERE mode = 'runtime' AND instr(artifacts_json, 'goal_continuation') > 0 " +
            "ORDER BY workflow_id LIMIT 1",
        ).use { rows ->
          check(rows.next()) { "no goal-continuation child found" }
          rows.getString(1)
        }
      }
    }

  private fun readAllRuntimeArtifactsJson(fixture: GoalCliFixture): List<String> =
    ensureTestDatabase(fixture.dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery(
          "SELECT workflow_id, artifacts_json FROM feature_task_workflows WHERE mode = 'runtime' ORDER BY workflow_id",
        ).use { rows ->
          buildList {
            while (rows.next()) {
              add(rows.getString(1) + "=" + rows.getString(2))
            }
          }
        }
      }
    }

  private fun readChildArtifacts(
    fixture: GoalCliFixture,
    workflowId: String,
  ): String =
    ensureTestDatabase(fixture.dbPath).use { connection ->
      connection.prepareStatement(
        "SELECT artifacts_json FROM feature_task_workflows WHERE workflow_id = ?",
      ).use { statement ->
        statement.setString(1, workflowId)
        statement.executeQuery().use { rows ->
          check(rows.next()) { "missing child $workflowId" }
          rows.getString(1)
        }
      }
    }

  private fun stripValidationDepth(
    fixture: GoalCliFixture,
    workflowId: String,
  ) {
    ensureTestDatabase(fixture.dbPath).use { connection ->
      val current =
        connection.prepareStatement(
          "SELECT artifacts_json FROM feature_task_workflows WHERE workflow_id = ?",
        ).use { statement ->
          statement.setString(1, workflowId)
          statement.executeQuery().use { rows ->
            check(rows.next()) { "missing child $workflowId" }
            rows.getString(1)
          }
        }
      val stripped =
        current
          .replace(Regex(""""validation_depth"\s*:\s*"full"\s*,?"""), "")
          .replace(",}", "}")
          .replace(",", ",")
      check(stripped != current) { "could not strip validation_depth from $workflowId" }
      connection.prepareStatement(
        "UPDATE feature_task_workflows SET artifacts_json = ? WHERE workflow_id = ?",
      ).use { statement ->
        statement.setString(1, stripped)
        statement.setString(2, workflowId)
        check(statement.executeUpdate() == 1)
      }
    }
  }

  private fun seedCompletedUpstreamMissingOutput(
    fixture: GoalCliFixture,
    workflowId: String,
  ) {
    ensureTestDatabase(fixture.dbPath).use { connection ->
      val current =
        connection.prepareStatement(
          "SELECT artifacts_json FROM feature_task_workflows WHERE workflow_id = ?",
        ).use { statement ->
          statement.setString(1, workflowId)
          statement.executeQuery().use { rows ->
            check(rows.next()) { "missing child $workflowId" }
            rows.getString(1)
          }
        }
      val artifacts = parseWorkflowArtifactsForTest(current).toMutableMap()
      val records = phaseRecordsFromWorkflowArtifacts(artifacts).toMutableMap()
      val timestamp = "2026-09-12T08:00:00Z"
      records.putIfAbsent(
        "verify_findings",
        FeatureTaskRuntimePhaseRecord(
          phaseId = "verify_findings",
          status = WorkflowStepStatus.COMPLETED,
          attemptCount = 1,
          startedAt = timestamp,
          resolvedAgentId = "codex",
        ),
      )
      records.putIfAbsent(
        "implement_fix",
        FeatureTaskRuntimePhaseRecord(
          phaseId = "implement_fix",
          status = WorkflowStepStatus.BLOCKED,
          attemptCount = 1,
          startedAt = timestamp,
          resolvedAgentId = "codex",
        ),
      )
      records["verify_findings"] =
        records.getValue("verify_findings").copy(
          status = WorkflowStepStatus.COMPLETED,
          finishedAt = null,
          durationMillis = null,
          outputArtifact = null,
        )
      records["implement_fix"] =
        records.getValue("implement_fix").copy(
          status = WorkflowStepStatus.BLOCKED,
          finishedAt = null,
          durationMillis = null,
          outputArtifact = null,
          blockedReason =
            "Phase 'implement_fix' requires upstream output(s) verify_findings that are not present",
        )
      artifacts[FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY] =
        records.mapValues { (_, record) -> record.asWorkflowArtifactEntry() }
      connection.prepareStatement(
        "UPDATE feature_task_workflows SET artifacts_json = ? WHERE workflow_id = ?",
      ).use { statement ->
        statement.setString(1, JsonCodec.mapToJsonString(artifacts))
        statement.setString(2, workflowId)
        check(statement.executeUpdate() == 1)
      }
    }
  }
}

private fun parseWorkflowArtifactsForTest(json: String): Map<String, Any?> =
  requireNotNull(
    JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(requireNotNull(JsonCodec.parseObjectOrNull(json)))),
  )
