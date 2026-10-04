package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliExecutionResult
import skillbill.di.core.SkillBillVersion
import skillbill.infrastructure.sqlite.ensureTestDatabase
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class CliGoalSharedPreplanReplanTest {
  @Test
  fun `include-shared-preplan lists cascade then relaunch regenerates without reopening terminals`() {
    val fixture = goalFixture(subtaskCount = 3)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    advanceToSubtaskThreeBoundary(fixture, launcher)
    assertSharedPreplanCascade(fixture, launcher)
    assertRelaunchRegeneratesIntoSubtaskThree(fixture, launcher)
  }

  private fun advanceToSubtaskThreeBoundary(
    fixture: GoalCliFixture,
    launcher: GoalFixtureAgentRunLauncher,
  ) {
    val advanced =
      CliRuntime.run(
        fixture.goalCommand(extra = listOf("--stop-after-subtask", "2")),
        fixture.context(launcher = launcher),
      )
    assertEquals(2, advanced.exitCode, advanced.stdout)
    assertEquals(listOf(1, 2), launcher.childLaunches.map { it.skillRunRequest.subtaskId })
    val statusBefore = goalStatus(fixture, launcher)
    assertContains(statusBefore.stdout, "shared_preplan=true")
    assertContains(statusBefore.stdout, "complete: 2")
    CliRuntime.run(goalControlCommand(fixture, "resume"), fixture.context(launcher = launcher))
  }

  private fun assertSharedPreplanCascade(
    fixture: GoalCliFixture,
    launcher: GoalFixtureAgentRunLauncher,
  ) {
    val replan =
      CliRuntime.run(
        listOf(
          "--db", fixture.dbPath.toString(),
          "goal", "replan", "SKILL-901",
          "--subtask", "3",
          "--include-shared-preplan",
          "--repo-root", fixture.tempDir.toString(),
        ),
        fixture.context(launcher = launcher),
      )
    assertEquals(0, replan.exitCode, replan.stdout)
    assertContains(replan.stdout, "discarded_shared_preplan: true")
    assertContains(replan.stdout, "cascaded_plans=")

    assertTrue(
      replan.stdout.contains("cascaded_plans=none"),
      replan.stdout,
    )
    assertEquals(true, replan.payload?.get("discarded_shared_preplan"))
    val cascaded = replan.payload?.get("cascaded_plan_subtask_ids") as? List<*>
    val cascadedIds = cascaded.orEmpty().mapNotNull { (it as? Number)?.toInt() ?: (it as? String)?.toIntOrNull() }
    assertTrue(1 !in cascadedIds, "complete+commit sibling 1 must not cascade")
    assertTrue(2 !in cascadedIds, "complete+commit sibling 2 must not cascade")
    assertFalse(3 in cascadedIds)
    val plannedAfter = (replan.payload?.get("after") as? Map<*, *>)?.get("planned_subtask_ids") as? List<*>
    val plannedAfterIds =
      plannedAfter.orEmpty().mapNotNull {
        (it as? Number)?.toInt() ?: (it as? String)?.toIntOrNull()
      }
    assertTrue(1 in plannedAfterIds, "complete+commit plan row 1 must survive include-shared-preplan")
    assertTrue(2 in plannedAfterIds, "complete+commit plan row 2 must survive include-shared-preplan")

    val clearedChildren =
      (replan.payload?.get("cleared_child_subtask_ids") as? List<*>)
        ?.mapNotNull { (it as? Number)?.toInt() ?: (it as? String)?.toIntOrNull() }
        .orEmpty()
    assertFalse(1 in clearedChildren, "a completed subtask's child must survive the cascade")
    assertFalse(2 in clearedChildren, "a completed subtask's child must survive the cascade")
    val statusAfter = goalStatus(fixture, launcher)
    assertContains(statusAfter.stdout, "shared_preplan=false")
    assertContains(statusAfter.stdout, "complete: 2")
    assertTrue(
      statusAfter.stdout.contains("planning_reason:") &&
        (statusAfter.stdout.contains("not started") || statusAfter.stdout.contains("preplan")),
      statusAfter.stdout,
    )
  }

  private fun assertRelaunchRegeneratesIntoSubtaskThree(
    fixture: GoalCliFixture,
    launcher: GoalFixtureAgentRunLauncher,
  ) {
    val planningBefore =
      launcher.requests.count {
        it.skillRunRequest.goalContinuation == null && it.skillRunRequest.promptOverride != null
      }
    launcher.childLaunches.clear()
    val relaunch = CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))
    assertEquals(0, relaunch.exitCode, relaunch.stdout + relaunch.stderr)
    assertEquals(listOf(3), launcher.childLaunches.map { it.skillRunRequest.subtaskId })
    val planningAfter =
      launcher.requests.count {
        it.skillRunRequest.goalContinuation == null && it.skillRunRequest.promptOverride != null
      }
    assertTrue(
      planningAfter > planningBefore,
      "relaunch must regenerate shared preplan/plans after opt-in discard",
    )
    assertContains(goalStatus(fixture, launcher).stdout, "complete: 3")
  }

  private fun goalStatus(
    fixture: GoalCliFixture,
    launcher: GoalFixtureAgentRunLauncher,
  ) = CliRuntime.run(
    listOf(
      "--db", fixture.dbPath.toString(),
      "goal", "status", "SKILL-901",
      "--agent", "codex",
      "--repo-root", fixture.tempDir.toString(),
    ),
    fixture.context(launcher = launcher),
  )
}

class CliGoalWatchRuntimeTest {
  @Test
  fun `goal watch resets two idle refreshes with live and stops only after three consecutive idle refreshes`() {
    val fixture = goalFixture(subtaskCount = 1)
    val childWorkflowId = startRunningRuntimeGoalChild(fixture)
    val resetOutput = StringBuilder()
    var observed = 0

    val reset =
      CliRuntime.run(
        listOf(
          "--db", fixture.dbPath.toString(),
          "goal", "watch", "SKILL-901",
          "--interval-seconds", "0",
          "--max-refreshes", "4",
          "--show-unchanged",
        ),
        fixture.context(
          launcher = NoopGoalTestAgentRunLauncher,
          liveStdout = {
            resetOutput.append(it)
            if (it.startsWith("watch_refresh:") && ++observed == 2) {
              seedLiveWorkerLease(fixture, childWorkflowId)
            }
          },
        ),
      )

    assertEquals("max_refreshes", reset.payload?.get("stop_reason"))
    assertEquals(4, reset.payload?.get("refresh_count"))
    assertContains(resetOutput.toString(), "index=3 status=ok")
    assertContains(resetOutput.toString(), "execution_liveness=live")

    clearWorkerLease(fixture, childWorkflowId)
    val idleOutput = StringBuilder()
    val idle =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "goal",
          "watch",
          "SKILL-901",
          "--interval-seconds",
          "0",
        ),
        fixture.context(
          launcher = NoopGoalTestAgentRunLauncher,
          liveStdout = { idleOutput.append(it) },
        ),
      )

    assertEquals("goal_idle", idle.payload?.get("stop_reason"))
    assertEquals(3, idle.payload?.get("refresh_count"))
    assertContains(idle.stdout, "watch_refresh: index=3 status=ok")
    assertContains(idle.stdout, "execution_liveness=idle")
    assertFalse(idleOutput.toString().contains("index=4"), idleOutput.toString())
  }

  @Test
  fun `goal watch refreshes read-only status without launching child runs`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    val liveStdout = StringBuilder()

    val watch =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "goal",
          "watch",
          "SKILL-901",
          "--agent",
          "codex",
          "--repo-root",
          fixture.tempDir.toString(),
          "--interval-seconds",
          "0",
          "--max-refreshes",
          "2",
          "--diff-stat",
        ),
        fixture.context(launcher = launcher, liveStdout = { liveStdout.append(it) }),
      )

    assertEquals(0, watch.exitCode, watch.stdout)
    assertContains(liveStdout.toString(), "watch_refresh: index=1 status=ok")
    assertEquals(false, watch.stdout.contains("watch_refresh: index=1 status=ok"), watch.stdout)
    assertContains(watch.stdout, "watch_refresh: index=2 status=ok")
    assertContains(liveStdout.toString(), "watch_diff_stat: index=1 files_changed=1 insertions=2 deletions=1")
    assertContains(watch.stdout, "watch_diff_stat: index=2 files_changed=1 insertions=2 deletions=1")
    assertEquals(null, watch.payload?.get("refreshes"))
    assertEquals(2, (watch.payload?.get("latest_refresh") as? Map<*, *>)?.get("refresh_index"))
    assertEquals(2, watch.payload?.get("refresh_count"))
    assertEquals("max_refreshes", watch.payload?.get("stop_reason"))
    assertEquals(emptyList(), launcher.requests)
  }

  @Test
  fun `goal watch explicit one shot preserves payload and does not sleep`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val watch =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "goal",
          "watch",
          "SKILL-901",
          "--agent",
          "codex",
          "--interval-seconds",
          "60",
          "--max-refreshes",
          "1",
        ),
        fixture.context(launcher = launcher),
      )

    assertEquals(0, watch.exitCode, watch.stdout)
    assertEquals("ok", watch.payload?.get("status"))
    assertEquals("SKILL-901", watch.payload?.get("issue_key"))
    assertEquals(1, watch.payload?.get("refresh_count"))
    assertEquals(60, watch.payload?.get("interval_seconds"))
    assertEquals("max_refreshes", watch.payload?.get("stop_reason"))
    assertEquals(1, (watch.payload?.get("latest_refresh") as? Map<*, *>)?.get("refresh_index"))
    assertEquals(emptyList(), launcher.requests)
  }

  @Test
  fun `goal watch accepts zero refresh bound and stops when goal is not found`() {
    val fixture = goalFixture(subtaskCount = 1)
    val liveOutput = StringBuilder()

    val watch =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "goal",
          "watch",
          "SKILL-404",
          "--interval-seconds",
          "60",
          "--max-refreshes",
          "0",
        ),
        fixture.context(
          launcher = NoopGoalTestAgentRunLauncher,
          liveStdout = { liveOutput.append(it) },
        ),
      )

    assertEquals(1, watch.exitCode, watch.stdout)
    assertEquals(1, watch.payload?.get("refresh_count"))
    assertEquals("not_found", watch.payload?.get("stop_reason"))
    assertContains(liveOutput.toString(), "watch_refresh: index=1 status=not_found")
  }

  @Test
  fun `goal watch rejects negative refresh bound`() {
    val fixture = goalFixture(subtaskCount = 1)

    val watch =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "goal",
          "watch",
          "SKILL-901",
          "--max-refreshes",
          "-1",
        ),
        fixture.context(launcher = NoopGoalTestAgentRunLauncher),
      )

    assertEquals(1, watch.exitCode, watch.stderr)
    assertContains(watch.stderr, "--max-refreshes must be non-negative")
  }

  @Test
  fun `goal watch default follows until the first terminal projection`() {
    val fixture = goalFixture(subtaskCount = 1)
    val childWorkflowId = startRunningGoalChild(fixture)
    recordRunningGoalChildProgress(fixture, childWorkflowId, sequence = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)
    val liveStdout = StringBuilder()
    var refreshesObserved = 0

    val watch =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "goal",
          "watch",
          "SKILL-901",
          "--agent",
          "codex",
          "--interval-seconds",
          "0",
        ),
        fixture.context(
          launcher = launcher,
          liveStdout = {
            liveStdout.append(it)
            if (it.startsWith("watch_refresh:") && ++refreshesObserved == 1) {
              completeRunningGoalChild(fixture, childWorkflowId)
            }
          },
        ),
      )

    assertEquals(0, watch.exitCode, watch.stdout)
    assertEquals(2, watch.payload?.get("refresh_count"))
    assertEquals("goal_terminal", watch.payload?.get("stop_reason"))
    assertContains(liveStdout.toString(), "watch_refresh: index=1 status=ok")
    assertContains(watch.stdout, "watch_refresh: index=2 status=ok")
    assertFalse(watch.stdout.contains("watch_refresh: index=3"), watch.stdout)
    assertEquals(emptyList(), launcher.requests)
  }

  @Test
  fun `goal watch blocked but pending continues until its explicit bound`() {
    val fixture = goalFixture(subtaskCount = 2)
    val launcher = GoalFixtureAgentRunLauncher(fixture, failSubtask = 1)
    val run = CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))
    assertEquals(1, run.exitCode, run.stdout)
    val launchCount = launcher.requests.size

    val watch =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "goal",
          "watch",
          "SKILL-901",
          "--agent",
          "codex",
          "--interval-seconds",
          "0",
          "--max-refreshes",
          "2",
        ),
        fixture.context(launcher = launcher),
      )

    val latest = watch.payload?.get("latest_refresh") as? Map<*, *>
    assertEquals(2, watch.payload?.get("refresh_count"))
    assertEquals("max_refreshes", watch.payload?.get("stop_reason"))
    assertEquals(1, latest?.get("blocked_count"))
    assertEquals(1, latest?.get("pending_count"))
    assertEquals(launchCount, launcher.requests.size)
  }

  @Test
  fun `goal watch shows unchanged refreshes by default and supports opt in suppression`() {
    val fixture = goalFixture(subtaskCount = 1)
    val childWorkflowId = startRunningGoalChild(fixture)
    recordRunningGoalChildProgress(fixture, childWorkflowId, sequence = 1)
    val suppressedOutput = StringBuilder()
    val shownOutput = StringBuilder()
    var suppressedRefreshesObserved = 0

    val suppressed =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "goal",
          "watch",
          "SKILL-901",
          "--interval-seconds",
          "0",
          "--max-refreshes",
          "3",
          "--suppress-unchanged",
        ),
        fixture.context(
          launcher = NoopGoalTestAgentRunLauncher,
          liveStdout = {
            suppressedOutput.append(it)
            if (it.startsWith("watch_refresh:") && ++suppressedRefreshesObserved == 1) {
              advanceRunningGoalChildToReview(fixture, childWorkflowId)
            }
          },
        ),
      )
    val shown =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "goal",
          "watch",
          "SKILL-901",
          "--interval-seconds",
          "0",
          "--max-refreshes",
          "3",
        ),
        fixture.context(
          launcher = NoopGoalTestAgentRunLauncher,
          liveStdout = { shownOutput.append(it) },
        ),
      )

    assertWatchRefreshRendering(suppressedOutput, shownOutput, suppressed, shown)
  }

  private fun assertWatchRefreshRendering(
    suppressedOutput: StringBuilder,
    shownOutput: StringBuilder,
    suppressed: CliExecutionResult,
    shown: CliExecutionResult,
  ) {
    assertEquals(3, suppressedOutput.lines().count { it.startsWith("watch_refresh:") })
    assertContains(
      suppressedOutput.toString(),
      "watch_refresh: index=1 status=ok current_subtask=1 current_step=implement",
    )
    assertContains(
      suppressedOutput.toString(),
      "watch_refresh: index=2 status=ok current_subtask=1 current_step=review",
    )
    assertEquals(3, shownOutput.lines().count { it.startsWith("watch_refresh:") })
    assertContains(suppressed.stdout, "watch_refresh: index=3 status=ok")
    assertContains(shown.stdout, "watch_refresh: index=3 status=ok")
    assertEquals(3, suppressed.payload?.get("refresh_count"))
    assertEquals(3, shown.payload?.get("refresh_count"))
  }
}

class CliGoalExecutionOptionsTest {
  @Test
  fun `goal max wall clock defaults to the telemetry-backed cap when flag absent`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val result =
      CliRuntime.run(
        fixture.goalCommand(extra = emptyList()),
        fixture.context(launcher = launcher),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(180.minutes, launcher.childLaunches.single().skillRunRequest.timeout)
  }

  @Test
  fun `goal max wall clock flag passes optional cap to child run`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val result =
      CliRuntime.run(
        fixture.goalCommand(extra = listOf("--max-wall-clock-minutes", "90")),
        fixture.context(launcher = launcher),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(90.minutes, launcher.childLaunches.single().skillRunRequest.timeout)
  }

  @Test
  fun `goal max wall clock zero disables the cap`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val result =
      CliRuntime.run(
        fixture.goalCommand(extra = listOf("--max-wall-clock-minutes", "0")),
        fixture.context(launcher = launcher),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(null, launcher.childLaunches.single().skillRunRequest.timeout)
  }

  @Test
  fun `goal no-live-output keeps launch monitoring but suppresses child output tee`() {
    val fixture = goalFixture(subtaskCount = 1)
    val liveStdout = StringBuilder()
    val liveStderr = StringBuilder()

    val result =
      CliRuntime.run(
        fixture.goalCommand(extra = listOf("--no-live-output")),
        fixture.context(
          launcher = GoalFixtureAgentRunLauncher(fixture),
          liveStdout = { liveStdout.append(it) },
          liveStderr = { liveStderr.append(it) },
        ),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(liveStdout.toString(), "goal SKILL-901: launched runtime executable=")
    assertContains(liveStdout.toString(), "version=${SkillBillVersion.VALUE} build_id=${SkillBillVersion.VALUE}")
    assertContains(liveStdout.toString(), "goal watch SKILL-901 --repo-root")
    assertFalse(liveStdout.toString().contains("heartbeat"), liveStdout.toString())
    assertEquals(false, liveStdout.toString().contains("child-1-stdout"), liveStdout.toString())
    assertEquals("", liveStderr.toString())
  }

  @Test
  fun `goal forced failure exits non-zero and status reports blocked projection`() {
    val fixture = goalFixture(subtaskCount = 3)
    val launcher = GoalFixtureAgentRunLauncher(fixture, failSubtask = 2)

    val result = CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "goal SKILL-901: failed at subtask 2")
    assertEquals(listOf(1, 2), launcher.childLaunches.map { it.skillRunRequest.subtaskId })
    assertEquals(0, fixture.pullRequests.requests.size)

    val status =
      CliRuntime.run(
        listOf("--db", fixture.dbPath.toString(), "goal", "status", "SKILL-901", "--agent", "codex"),
        fixture.context(launcher = launcher),
      )

    assertEquals(0, status.exitCode, status.stdout)
    assertContains(status.stdout, "complete: 1")
    assertContains(status.stdout, "pending: 1")
    assertContains(status.stdout, "blocked: 1")
    assertContains(status.stdout, "current_subtask: 2")
    assertContains(status.stdout, "current_step: implement")

    assertContains(status.stdout, "active_agent: none")
  }

  @Test
  fun `goal no terminal outcome marks child workflow blocked`() {
    val fixture = goalFixture(subtaskCount = 2)
    val launcher = GoalFixtureAgentRunLauncher(fixture, noTerminalSubtask = 1)

    val result = CliRuntime.run(fixture.goalCommand(), fixture.context(launcher = launcher))

    assertEquals(3, result.exitCode, result.stdout)
    assertContains(result.stdout, "goal SKILL-901: blocked at subtask 1")
    assertContains(result.stdout, "without a terminal workflow-store outcome")
    val workflowId = result.payload?.get("workflow_id")?.toString().orEmpty()
    assertTrue(workflowId.isNotBlank())
    val child =
      RuntimeWorkflowTestSupport.get(
        fixture.dbPath,
        workflowId,
        fixture.context(launcher = launcher),
      )

    assertEquals("blocked", child["workflow_status"])

    assertEquals("implement", child["current_step_id"])
  }

  @Test
  fun `goal imports checked-in decomposition manifest when workflow store is missing`() {
    val fixture = goalFixture(subtaskCount = 1)
    val recoveredDb = fixture.tempDir.resolve("recovered.db")
    val launcher = GoalFixtureAgentRunLauncher(fixture, dbPath = recoveredDb)

    val result =
      CliRuntime.run(
        fixture.goalCommand(dbPath = recoveredDb),
        fixture.context(launcher = launcher),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "goal SKILL-901: finished")
    DriverManager.getConnection("jdbc:sqlite:$recoveredDb").use { connection ->
      connection.prepareStatement("SELECT COUNT(*) FROM feature_task_workflows").use { statement ->
        statement.executeQuery().use { rows ->
          assertTrue(rows.next())
          assertTrue(rows.getInt(1) >= 1, "the recovered database must hold the imported child workflow")
        }
      }
    }
  }

  @Test
  fun `goal status reads checked-in decomposition manifest without importing workflow state`() {
    val fixture = goalFixture(subtaskCount = 1)
    val recoveredDb = fixture.tempDir.resolve("status-recovered.db")

    val status =
      CliRuntime.run(
        listOf(
          "--db",
          recoveredDb.toString(),
          "goal",
          "status",
          "SKILL-901",
          "--agent",
          "codex",
          "--repo-root",
          fixture.tempDir.toString(),
        ),
        fixture.context(launcher = NoopGoalTestAgentRunLauncher),
      )

    assertEquals(0, status.exitCode, status.stdout)
    assertContains(status.stdout, "status: ok")
    assertContains(status.stdout, "current_subtask: 1")
    DriverManager.getConnection("jdbc:sqlite:$recoveredDb").use { connection ->
      connection.prepareStatement("SELECT COUNT(*) FROM feature_task_workflows WHERE issue_key = ?").use { statement ->
        statement.setString(1, "SKILL-901")
        statement.executeQuery().use { rows ->
          assertTrue(rows.next())
          assertEquals(0, rows.getInt(1))
        }
      }
    }

    assertContains(status.stdout, "active_agent: none")
  }

  @Test
  fun `goal status prefers authoritative complete child without mutating stale running child workflow`() {
    val fixture = goalFixture(subtaskCount = 1)
    val staleChild = startRunningGoalChild(fixture)
    recordRunningGoalChildProgress(fixture, staleChild, sequence = 9, message = "stale active event")
    seedAuthoritativeCompleteChild(fixture)

    val status =
      CliRuntime.run(
        listOf("--db", fixture.dbPath.toString(), "goal", "status", "SKILL-901", "--agent", "codex"),
        fixture.context(launcher = NoopGoalTestAgentRunLauncher),
      )
    val staleWorkflow =
      RuntimeWorkflowTestSupport.get(
        fixture.dbPath,
        staleChild,
        fixture.context(launcher = NoopGoalTestAgentRunLauncher),
      )

    assertEquals(0, status.exitCode, status.stdout)
    assertContains(status.stdout, "complete: 1")
    assertContains(status.stdout, "pending: 0")
    assertContains(status.stdout, "blocked: 0")
    assertContains(status.stdout, "current_subtask: none")
    assertEquals(false, status.stdout.contains("latest_observability:"), status.stdout)
    assertEquals("running", staleWorkflow["workflow_status"])
    assertEquals(false, staleWorkflow["artifacts"]?.toString().orEmpty().contains("stale running child"))
  }
}

class CliGoalUnaddressedFindingsTest {
  @Test
  fun `an unreadable ledger reports itself instead of an affirmative zero`() {
    val fixture = goalFixture(subtaskCount = 1)
    ensureTestDatabase(fixture.dbPath).use { connection ->
      connection.prepareStatement(
        "INSERT INTO unaddressed_findings (issue_key, workflow_id, subtask_id, review_pass_number, " +
          "finding_ordinal, severity, issue_category, location, summary) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
      ).use { statement ->
        statement.setString(1, "SKILL-901")
        statement.setString(2, "wftr-poison-1")
        statement.setInt(3, 1)
        statement.setInt(4, 1)
        statement.setInt(5, 1)
        statement.setString(6, "critical")
        statement.setString(7, "behavior_correctness")
        statement.setString(8, "src/Feature.kt:42")
        statement.setString(9, "Poison row persisted by an older writer")
        statement.executeUpdate()
      }
    }

    val result =
      CliRuntime.run(
        fixture.goalCommand(),
        fixture.context(launcher = GoalFixtureAgentRunLauncher(fixture)),
      )

    assertContains(result.stdout, "goal SKILL-901: finished")
    assertFalse(result.stdout.contains("Poison row persisted"))
    assertFalse(result.stdout.contains("unaddressed_findings"))
  }
}

class CliGoalTransitionMonitoringTest {
  @Test
  fun `goal run emits only launch monitoring and one bounded terminal notification`() {
    val fixture = goalFixture(subtaskCount = 2)
    val liveStdout = StringBuilder()
    val launcher = GoalFixtureAgentRunLauncher(fixture, failSubtask = 2, childDiagnosticChatterCount = 8)

    val result =
      CliRuntime.run(
        fixture.goalCommand(),
        fixture.context(
          launcher = launcher,
          liveStdout = { liveStdout.append(it) },
        ),
      )

    assertEquals(1, result.exitCode, result.stdout)
    val output = liveStdout.toString()
    assertEquals(1, output.lines().count { it.startsWith("goal SKILL-901: launched") })
    assertContains(output, "goal watch SKILL-901 --repo-root")
    assertContains(output, "goal status SKILL-901 --repo-root")
    assertFalse(output.contains("goal_event:"), output)
    assertFalse(output.contains("heartbeat"), output)
    assertFalse(output.contains("goal_observability:"), output)
    assertFalse(output.contains("child-1-stdout"), output)
    assertFalse(output.contains("child-1-stderr"), output)
    assertContains(result.stdout, "goal SKILL-901: failed at subtask 2")
    assertEquals(1, result.stdout.lines().count { it.isNotBlank() })
  }
}

class CliGoalProgressIdleTimeoutTest {
  @Test
  fun `goal progress idle timeout flag passes value to child run`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val result =
      CliRuntime.run(
        fixture.goalCommand(extra = listOf("--progress-idle-timeout-minutes", "25")),
        fixture.context(launcher = launcher),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(25.minutes, launcher.childLaunches.single().skillRunRequest.progressIdleTimeout)
  }

  @Test
  fun `goal progress idle timeout defaults to a bounded cap when flag absent`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val result =
      CliRuntime.run(
        fixture.goalCommand(extra = emptyList()),
        fixture.context(launcher = launcher),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(10.minutes, launcher.childLaunches.single().skillRunRequest.progressIdleTimeout)
  }

  @Test
  fun `goal progress idle timeout zero disables the cap`() {
    val fixture = goalFixture(subtaskCount = 1)
    val launcher = GoalFixtureAgentRunLauncher(fixture)

    val result =
      CliRuntime.run(
        fixture.goalCommand(extra = listOf("--progress-idle-timeout-minutes", "0")),
        fixture.context(launcher = launcher),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(null, launcher.childLaunches.single().skillRunRequest.progressIdleTimeout)
  }
}
