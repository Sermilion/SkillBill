package skillbill.engine.goalrunner.persist

import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.RecordingPullRequestPort
import skillbill.engine.goalrunner.RecordingSubtaskLauncher
import skillbill.engine.goalrunner.completeOutcome
import skillbill.engine.goalrunner.execution.core.goalRunnerDeps
import skillbill.engine.goalrunner.execution.core.testGoalRunner
import skillbill.engine.goalrunner.launchFacts
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.goalrunner.model.GoalRunnerLivenessState.IDLE
import skillbill.goalrunner.model.GoalRunnerLivenessState.PROGRESSING
import skillbill.goalrunner.model.GoalRunnerLivenessState.WORKING
import skillbill.goalrunner.model.GoalRunnerProcessState.CONFIRMED_ALIVE
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.agentrun.model.AgentRunLivenessSnapshot
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.withWorkflowId
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import skillbill.goalrunner.model.GoalRunnerProcessState.IDLE as PROCESS_IDLE
import skillbill.goalrunner.model.GoalRunnerProcessState.PROGRESSING as PROCESS_PROGRESSING

class GoalRunnerLedgerTest {
  @Test
  fun `first start records child activation terminal done check and final reconciled outcome`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts().copy(childSessionPath = "/work/child-1", childSessionId = "claude:SKILL-56:subtask-1")
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    assertIs<GoalRunnerRunReport.Completed>(runner.run(ledgerRunRequest()))

    val actions = ledgerActions(outcomes)
    assertContains(actions, "child_activation")
    assertContains(actions, "terminal_done_check")
    assertContains(actions, "final_reconciled_outcome")
    val terminalDoneCheck =
      outcomes.attemptLedgerEntries
        .first { it.action.wireValue == "terminal_done_check" }
    assertContains(requireNotNull(terminalDoneCheck.finalReconciledResult), "complete")
    val finalReconciled =
      outcomes.attemptLedgerEntries
        .first { it.action.wireValue == "final_reconciled_outcome" }
    assertContains(requireNotNull(finalReconciled.finalReconciledResult), "goal_finalize")

    assertEquals(
      outcomes.attemptLedgerEntries.map { it.sequenceNumber }.sorted(),
      outcomes.attemptLedgerEntries.map { it.sequenceNumber },
    )

    val activation =
      outcomes.attemptLedgerEntries
        .first { it.action.wireValue == "child_activation" }
    assertEquals("/work/child-1", activation.childSessionPath)
    assertEquals("claude:SKILL-56:subtask-1", activation.childSessionId)
  }

  @Test
  fun `resume selection records a resume ledger action for a previously blocked subtask`() {
    val initial =
      manifest(subtaskCount = 1).copy(
        status = "blocked",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "blocked"),
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 1,
              name = "Subtask 1",
              specPath = ".feature-specs/SKILL-56-goal/spec_subtask_1.md",
              status = "blocked",
              workflowId = "wfl-1",
              blockedReason = "validation failed",
              lastResumableStep = "validate",
            ),
          ),
      )
    val store = InMemoryGoalManifestStore(manifest = initial)
    val outcomes = RecordingOutcomeStore()
    outcomes.seedReviewState("wfl-1")
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts()
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    assertIs<GoalRunnerRunReport.Completed>(runner.run(ledgerRunRequest()))

    val actions = ledgerActions(outcomes)
    assertContains(actions, "resume")
    assertFalse(actions.contains("child_activation"), "a resume must not be recorded as a child activation: $actions")
  }

  @Test
  fun `no terminal store outcome records a retry ledger action`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        launchFacts()
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(ledgerRunRequest()))
    assertEquals(GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME, stopped.stop.reason)

    val retry = outcomes.attemptLedgerEntries.firstOrNull { it.action.wireValue == "retry" }
    assertTrue(retry != null, "expected a retry ledger entry: ${ledgerActions(outcomes)}")
    assertEquals("no_terminal_store_outcome", retry.stopReason)
  }

  @Test
  fun `prefixless implementation result records missing result prefix diagnostics and recovered output`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val prefixlessJson =
      """
      {
        "tasks_completed": 1,
        "files_created": [],
        "files_modified": ["runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/GoalRunner.kt"],
        "tests_written": [],
        "plan_deviation_notes": "",
        "criteria_to_file_map": {},
        "notes_for_review": "F-001",
        "progress_write_failures": [],
        "stopped_early": false,
        "stopped_reason": ""
      }
      """.trimIndent()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        launchFacts(stdout = prefixlessJson)
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(ledgerRunRequest()))

    assertEquals(GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME, stopped.stop.reason)
    val finalStop = outcomes.attemptLedgerEntries.last { it.stopReason == "no_terminal_store_outcome" }
    assertEquals("missing_result_prefix", finalStop.diagnosticClass)
    assertTrue(finalStop.recoverableJsonPresent == true)
    assertEquals("resume_from_last_resumable_step", finalStop.nextSafeAction)
    val recovered = outcomes.recoveredMissingResultPrefixOutputs.single()
    assertEquals("wfl-1", recovered.workflowId)
    assertEquals(
      "F-001",
      recovered.output["notes_for_review"],
    )
  }

  @Test
  fun `malformed or ambiguous child json records malformed result diagnostics without recovery`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        launchFacts(stdout = """{"tasks_completed":1} {"tasks_completed":2}""")
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(ledgerRunRequest()))

    assertEquals(GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME, stopped.stop.reason)
    val finalStop = outcomes.attemptLedgerEntries.last { it.stopReason == "no_terminal_store_outcome" }
    assertEquals("malformed_result_json", finalStop.diagnosticClass)
    assertFalse(finalStop.recoverableJsonPresent == true)
    assertEquals("inspect_child_output_then_resume", finalStop.nextSafeAction)
    assertTrue(outcomes.recoveredMissingResultPrefixOutputs.isEmpty())
  }

  @Test
  fun `timeout stop reason records a timeout ledger action that explains the stop`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        launchFacts(AgentRunTermination.TimedOut)
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(ledgerRunRequest()))
    assertEquals(GoalRunnerStopReason.TIMEOUT, stopped.stop.reason)

    val timeout = outcomes.attemptLedgerEntries.first { it.action.wireValue == "timeout" }
    assertEquals("timeout", timeout.stopReason)
  }

  @Test
  fun `interruption stop reason records an interruption ledger action`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        launchFacts(AgentRunTermination.Interrupted)
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(ledgerRunRequest()))
    assertEquals(GoalRunnerStopReason.INTERRUPTED, stopped.stop.reason)

    val interruption = outcomes.attemptLedgerEntries.first { it.action.wireValue == "interruption" }
    assertEquals("interrupted", interruption.stopReason)
  }

  @Test
  fun `policy blocked records a policy block ledger action without scraping provider logs`() {
    val store =
      InMemoryGoalManifestStore(
        manifest = manifest(subtaskCount = 1).copy(featureBranch = "main"),
      )
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { launchFacts() }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(ledgerRunRequest()))
    assertEquals(GoalRunnerStopReason.POLICY_BLOCKED, stopped.stop.reason)

    val policyBlock = outcomes.attemptLedgerEntries.first { it.action.wireValue == "policy_block" }
    assertEquals("policy_blocked", policyBlock.stopReason)
    assertContains(requireNotNull(policyBlock.blockedReason), "protected branch")
  }

  @Test
  fun `failed subtask records a final reconciled result with the failure explanation`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] =
          GoalRunnerStoredOutcome(
            status = GoalRunnerTerminalStatus.FAILED,
            workflowId = "wfl-$subtaskId",
            blockedReason = "review failed",
            lastResumableStep = "review",
            suppressPr = true,
          )
        launchFacts()
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(ledgerRunRequest()))
    assertEquals(GoalRunnerStopReason.FAILED, stopped.stop.reason)

    val failedEntry = outcomes.attemptLedgerEntries.last { it.stopReason == "failed" }
    assertEquals("failed", failedEntry.finalReconciledResult)
    assertContains(requireNotNull(failedEntry.blockedReason), "review failed")
  }

  @Test
  fun `confirmed-alive kill emits supervisor-killed-confirmed-alive diagnostic class`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        launchFacts(AgentRunTermination.TimedOut).copy(
          liveness =
            AgentRunLivenessSnapshot(
              phase = "review",
              reason = "idle_timeout",
              processState = CONFIRMED_ALIVE,
              livenessState = WORKING,
            ),
        )
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(ledgerRunRequest()))
    assertEquals(GoalRunnerStopReason.TIMEOUT, stopped.stop.reason)

    val timeoutEntry = outcomes.attemptLedgerEntries.last { it.stopReason == "timeout" }
    assertEquals(
      GoalRunnerLaunchFacts.DIAGNOSTIC_CLASS_CONFIRMED_ALIVE_KILL,
      timeoutEntry.diagnosticClass,
      "a kill of a confirmed-alive process must emit the distinct supervisor_killed_confirmed_alive class",
    )
  }

  @Test
  fun `progressing state also qualifies as confirmed-alive and emits the distinct diagnostic class`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        launchFacts(AgentRunTermination.TimedOut).copy(
          liveness =
            AgentRunLivenessSnapshot(
              phase = "implement",
              reason = "idle_timeout",
              processState = PROCESS_PROGRESSING,
              livenessState = PROGRESSING,
            ),
        )
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(ledgerRunRequest()))
    assertEquals(GoalRunnerStopReason.TIMEOUT, stopped.stop.reason)

    val timeoutEntry = outcomes.attemptLedgerEntries.last { it.stopReason == "timeout" }
    assertEquals(
      GoalRunnerLaunchFacts.DIAGNOSTIC_CLASS_CONFIRMED_ALIVE_KILL,
      timeoutEntry.diagnosticClass,
      "a kill of a PROGRESSING process must also emit the supervisor_killed_confirmed_alive class",
    )
  }

  @Test
  fun `non-confirmed-alive timeout keeps the standard child-process-failed diagnostic class`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        launchFacts(AgentRunTermination.TimedOut).copy(
          liveness =
            AgentRunLivenessSnapshot(
              phase = "preplan",
              reason = "idle_timeout",
              processState = PROCESS_IDLE,
              livenessState = IDLE,
            ),
        )
      }
    val runner = testGoalRunner(goalRunnerDeps(store, launcher, outcomes, RecordingPullRequestPort()))

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(ledgerRunRequest()))
    assertEquals(GoalRunnerStopReason.TIMEOUT, stopped.stop.reason)

    val timeoutEntry = outcomes.attemptLedgerEntries.last { it.stopReason == "timeout" }
    assertEquals(
      "child_process_failed",
      timeoutEntry.diagnosticClass,
      "an idle process timeout must keep the standard child_process_failed diagnostic class",
    )
  }

  private fun ledgerActions(outcomes: RecordingOutcomeStore): List<String> =
    outcomes.attemptLedgerEntries.map { it.action.wireValue }

  private fun ledgerRunRequest(): GoalRunnerRunRequest =
    GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      invokedAgentId = "claude",
    )
}
