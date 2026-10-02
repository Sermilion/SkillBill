package skillbill.engine.goalrunner.telemetry

import skillbill.application.telemetry.lifecycle.GoalLifecycleTelemetryEmitter
import skillbill.application.telemetry.lifecycle.noopGoalLifecycleTelemetryEmitter
import skillbill.application.telemetry.model.GoalFinishedRequest
import skillbill.application.telemetry.model.GoalIssueFinishedRequest
import skillbill.application.telemetry.model.GoalStartedRequest
import skillbill.application.telemetry.model.GoalSubtaskFinishedRequest
import skillbill.engine.goalrunner.GoalRunner
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.RecordingPullRequestPort
import skillbill.engine.goalrunner.RecordingSubtaskLauncher
import skillbill.engine.goalrunner.completeOutcome
import skillbill.engine.goalrunner.execution.core.fixedClock
import skillbill.engine.goalrunner.execution.core.goalRunnerDeps
import skillbill.engine.goalrunner.execution.core.testGoalRunner
import skillbill.engine.goalrunner.launchFacts
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerEventSink
import skillbill.engine.goalrunner.model.GoalRunnerRunEvent
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.withWorkflowId
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalRunnerTelemetryTest {
  @Test
  fun `preflight policy block emits one started and one blocked finished for the same segment`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1).copy(featureBranch = "main"))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { launchFacts() }
    val telemetry = RecordingGoalLifecycleTelemetryEmitter()
    val runner = telemetryRunner(store, launcher, outcomes, telemetry)

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Stopped>(report)
    val started = telemetry.started.single()
    val finished = telemetry.finished.single()
    assertEquals(started.workflowId, finished.workflowId)
    assertEquals("wfl-parent", started.parentWorkflowId)
    assertEquals("wfl-parent", finished.parentWorkflowId)
    assertEquals("blocked", finished.status)
    assertEquals("POLICY_BLOCKED", finished.stopReason)
    assertTrue(launcher.requests.isEmpty())
  }

  @Test
  fun `preflight policy block contributes exactly one fresh invocation to issue aggregates`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1).copy(featureBranch = "main"))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { launchFacts() }
    val telemetry = RecordingGoalLifecycleTelemetryEmitter()
    val runner = telemetryRunner(store, launcher, outcomes, telemetry)

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Stopped>(report)
    val started = telemetry.started.single()
    assertTrue(!started.resumed)
    assertEquals("SKILL-56", started.issueKey)
    assertEquals("wfl-parent", started.parentWorkflowId)
    val finished = telemetry.finished.single()
    assertEquals("blocked", finished.status)
    assertEquals("POLICY_BLOCKED", finished.stopReason)
    assertEquals("wfl-parent", finished.parentWorkflowId)
    assertTrue(telemetry.subtaskFinished.isEmpty())
    assertTrue(telemetry.issueFinished.isEmpty())
    assertTrue(launcher.requests.isEmpty())
  }

  @Test
  fun `clean completed run emits one started two subtask finished one finished and one issue finished`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
    val outcomes = RecordingOutcomeStore()
    val launcher = completingLauncher(store, outcomes)
    val telemetry = RecordingGoalLifecycleTelemetryEmitter()
    val runner = telemetryRunner(store, launcher, outcomes, telemetry)

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    val started = telemetry.started.single()
    assertEquals("SKILL-56", started.issueKey)
    assertEquals("goal", started.featureName)
    assertEquals(SEGMENT_WORKFLOW_ID, started.workflowId)
    assertEquals(2, started.subtaskTotal)
    assertTrue(!started.resumed)
    assertEquals(FIXED_INSTANT, started.startedAt)

    assertEquals(listOf(1, 2), telemetry.subtaskFinished.map { it.subtaskId })
    assertEquals(listOf("complete", "complete"), telemetry.subtaskFinished.map { it.status })
    assertEquals(listOf("wfl-1", "wfl-2"), telemetry.subtaskFinished.map { it.workflowId })
    telemetry.subtaskFinished.forEach { event ->
      assertEquals(1, event.attemptCount)
      assertEquals(0L, event.durationMs)
      assertEquals(FIXED_INSTANT, event.startedAt)
      assertEquals(FIXED_INSTANT, event.finishedAt)
      assertNull(event.blockedReason)
    }

    val finished = telemetry.finished.single()
    assertEquals(SEGMENT_WORKFLOW_ID, finished.workflowId)
    assertEquals("completed", finished.status)
    assertEquals(2, finished.subtasksComplete)
    assertEquals(0, finished.subtasksBlocked)
    assertEquals(0, finished.subtasksSkipped)
    assertEquals(FIXED_INSTANT, finished.startedAt)

    assertEquals(
      1,
      telemetry.issueFinished.size,
      "Completed GoalRunner reports must emit exactly one issue terminal event.",
    )
    val issueFinished = telemetry.issueFinished.single()
    assertEquals("SKILL-56", issueFinished.issueKey)
    assertEquals("completed", issueFinished.status)
    assertEquals("wfl-parent", issueFinished.parentWorkflowId)
    assertEquals(2, issueFinished.subtasksComplete)
    assertEquals(0, issueFinished.subtasksBlocked)
    assertEquals(0, issueFinished.subtasksSkipped)
    assertEquals(FIXED_INSTANT, issueFinished.finishedAt)
    assertEquals("runtime", issueFinished.mode)
  }

  @Test
  fun `run blocked mid-subtask emits finished events for terminal subtasks and a blocked goal_finished`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 3))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
        outcomes["wfl-$subtaskId"] =
          if (subtaskId == 2) {
            GoalRunnerStoredOutcome(
              status = GoalRunnerTerminalStatus.FAILED,
              workflowId = "wfl-2",
              blockedReason = "review failed",
              lastResumableStep = "review",
              suppressPr = true,
            )
          } else {
            completeOutcome(subtaskId)
          }
        launchFacts()
      }
    val telemetry = RecordingGoalLifecycleTelemetryEmitter()
    val runner = telemetryRunner(store, launcher, outcomes, telemetry)

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Stopped>(report)
    val started = telemetry.started.single()
    assertTrue(!started.resumed)

    assertEquals(listOf(1, 2), telemetry.subtaskFinished.map { it.subtaskId })
    assertEquals(listOf("complete", "blocked"), telemetry.subtaskFinished.map { it.status })
    val blocked = telemetry.subtaskFinished.single { it.subtaskId == 2 }
    assertTrue(requireNotNull(blocked.blockedReason).startsWith("runtime:"))
    assertContains(requireNotNull(blocked.blockedReason), "review failed")

    val finished = telemetry.finished.single()
    assertEquals("blocked", finished.status)
    assertEquals("FAILED", finished.stopReason)
    assertEquals(1, finished.subtasksComplete)
    assertEquals(1, finished.subtasksBlocked)
    assertEquals(0, finished.subtasksSkipped)
  }

  @Test
  fun `blocked segments carry enum stop reasons and completed issue emits once with parent workflow id`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val telemetry = RecordingGoalLifecycleTelemetryEmitter()
    val blockedLauncher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-blocked") }
        outcomes["wfl-blocked"] =
          GoalRunnerStoredOutcome(
            status = GoalRunnerTerminalStatus.FAILED,
            workflowId = "wfl-blocked",
            blockedReason = "review failed",
            lastResumableStep = "review",
            suppressPr = true,
          )
        launchFacts()
      }

    telemetryRunner(store, blockedLauncher, outcomes, telemetry).run(runRequest())
    store.mutate { current -> current.withBlockedSubtaskState(1, "wfl-blocked", "review failed") }
    telemetryRunner(store, blockedLauncher, outcomes, telemetry).run(runRequest())
    outcomes["wfl-blocked"] = completeOutcome(1)
    telemetryRunner(store, completingLauncher(store, outcomes), outcomes, telemetry).run(runRequest())

    assertEquals(listOf("FAILED", "FAILED"), telemetry.finished.take(2).map { it.stopReason })
    assertEquals(listOf(false, true, true), telemetry.started.map { it.resumed })
    assertEquals(
      1,
      telemetry.issueFinished.size,
      "Completing a previously blocked goal must emit one issue terminal event.",
    )
    val issueFinished = telemetry.issueFinished.single()
    assertEquals("completed", issueFinished.status)
    assertEquals("wfl-parent", issueFinished.parentWorkflowId)
    assertEquals(1, issueFinished.subtasksComplete)
  }

  @Test
  fun `resumed run emits finished only for current-segment terminals and never double-counts`() {
    val initial =
      manifest(subtaskCount = 3)
        .withCompletedSubtaskState(1, workflowId = "wfl-1", commitSha = "sha-1")
        .withBlockedSubtaskState(2, workflowId = "wfl-2", reason = "validation failed")
    val store = InMemoryGoalManifestStore(manifest = initial)
    val outcomes = RecordingOutcomeStore()
    outcomes["wfl-2"] = completeOutcome(2)
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        if (subtaskId == 3) {
          store.mutate { current -> current.withWorkflowId(3, "wfl-3") }
          outcomes["wfl-3"] = completeOutcome(3)
        }
        launchFacts()
      }
    val telemetry = RecordingGoalLifecycleTelemetryEmitter()
    val runner = telemetryRunner(store, launcher, outcomes, telemetry)

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertTrue(telemetry.started.single().resumed)
    assertEquals(listOf(3), telemetry.subtaskFinished.map { it.subtaskId })
    assertEquals("complete", telemetry.subtaskFinished.single().status)
    assertEquals("wfl-3", telemetry.subtaskFinished.single().workflowId)

    val finished = telemetry.finished.single()
    assertEquals("completed", finished.status)
    assertEquals(3, finished.subtasksComplete)
    assertEquals(0, finished.subtasksBlocked)
    assertEquals(0, finished.subtasksSkipped)
  }

  @Test
  fun `skipped subtask emits a skipped goal_subtask_finished counted in the segment summary`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current ->
          current.withWorkflowId(subtaskId, "wfl-$subtaskId").withSkippedSubtaskState(2)
        }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts()
      }
    val telemetry = RecordingGoalLifecycleTelemetryEmitter()
    val runner = telemetryRunner(store, launcher, outcomes, telemetry)

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(setOf(1, 2), telemetry.subtaskFinished.map { it.subtaskId }.toSet())
    val skipped = telemetry.subtaskFinished.single { it.subtaskId == 2 }
    assertEquals("skipped", skipped.status)
    assertEquals("SKILL-56:subtask:2", skipped.workflowId)
    assertEquals(0L, skipped.durationMs)

    val finished = telemetry.finished.single()
    assertEquals("completed", finished.status)
    assertEquals(1, finished.subtasksComplete)
    assertEquals(1, finished.subtasksSkipped)
  }

  @Test
  fun `issue finished counts skipped subtasks separately from completed ones`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 5))
    val outcomes = RecordingOutcomeStore()
    val launcher =
      RecordingSubtaskLauncher { request ->
        val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
        store.mutate { current ->
          current.withWorkflowId(subtaskId, "wfl-$subtaskId").withSkippedSubtaskState(3)
        }
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts()
      }
    val telemetry = RecordingGoalLifecycleTelemetryEmitter()
    val runner = telemetryRunner(store, launcher, outcomes, telemetry)

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    val issueFinished = telemetry.issueFinished.single()
    assertEquals(4, issueFinished.subtasksComplete)
    assertEquals(1, issueFinished.subtasksSkipped)
    assertEquals(0, issueFinished.subtasksBlocked)
  }

  @Test
  fun `telemetry write failure on goal_started fails the run loudly without swallowing`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher = completingLauncher(store, outcomes)
    val telemetry = RecordingGoalLifecycleTelemetryEmitter(failOn = setOf(TelemetryEvent.STARTED))
    val runner = telemetryRunner(store, launcher, outcomes, telemetry)

    val failure = assertFailsWith<TelemetryWriteFailure> { runner.run(runRequest()) }
    assertEquals(TelemetryEvent.STARTED, failure.event)
    assertTrue(telemetry.subtaskFinished.isEmpty())
    assertTrue(telemetry.finished.isEmpty())
  }

  @Test
  fun `telemetry write failure on goal_subtask_finished fails the run loudly`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher = completingLauncher(store, outcomes)
    val telemetry = RecordingGoalLifecycleTelemetryEmitter(failOn = setOf(TelemetryEvent.SUBTASK_FINISHED))
    val runner = telemetryRunner(store, launcher, outcomes, telemetry)

    val failure = assertFailsWith<TelemetryWriteFailure> { runner.run(runRequest()) }
    assertEquals(TelemetryEvent.SUBTASK_FINISHED, failure.event)
    assertEquals(1, telemetry.started.size)
    assertTrue(telemetry.finished.isEmpty())
  }

  @Test
  fun `NONE-default run is behaviorally identical to a run with a recording emitter`() {
    val report = { telemetry: GoalLifecycleTelemetryEmitter ->
      val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
      val outcomes = RecordingOutcomeStore()
      val events = mutableListOf<GoalRunnerRunEvent>()
      val runner =
        testGoalRunner(
          goalRunnerDeps(
            manifestStore = store,
            subtaskLauncher = completingLauncher(store, outcomes),
            outcomeStore = outcomes,
            pullRequestPort = RecordingPullRequestPort(),
          ).copy(
            telemetry = telemetry,
            clock = fixedClock(),
          ),
        )
      val result = runner.run(runRequest { events += it })
      Triple(result, store.manifest.status, events)
    }

    val none = report(noopGoalLifecycleTelemetryEmitter)
    val recording = report(RecordingGoalLifecycleTelemetryEmitter())

    assertEquals(none.second, recording.second)
    assertEquals(none.third, recording.third)
    assertIs<GoalRunnerRunReport.Completed>(none.first)
    assertIs<GoalRunnerRunReport.Completed>(recording.first)
  }

  private fun telemetryRunner(
    store: InMemoryGoalManifestStore,
    launcher: RecordingSubtaskLauncher,
    outcomes: RecordingOutcomeStore,
    telemetry: GoalLifecycleTelemetryEmitter,
  ): GoalRunner =
    testGoalRunner(
      goalRunnerDeps(
        manifestStore = store,
        subtaskLauncher = launcher,
        outcomeStore = outcomes,
        pullRequestPort = RecordingPullRequestPort(),
      ).copy(
        telemetry = telemetry,
        clock = fixedClock(),
      ),
    )

  private fun completingLauncher(
    store: InMemoryGoalManifestStore,
    outcomes: RecordingOutcomeStore,
  ): RecordingSubtaskLauncher =
    RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      launchFacts()
    }

  private fun runRequest(eventSink: (GoalRunnerRunEvent) -> Unit = {}): GoalRunnerRunRequest =
    GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      invokedAgentId = "claude",
      eventSink = GoalRunnerEventSink { eventSink(it) },
    )

  private fun fixedClock(): Clock = Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC)

  private companion object {
    const val FIXED_INSTANT: String = "2026-06-05T12:00:00Z"
    const val SEGMENT_WORKFLOW_ID: String = "wfl-parent:seg:$FIXED_INSTANT"
  }
}

private enum class TelemetryEvent { STARTED, SUBTASK_FINISHED, FINISHED }

private class TelemetryWriteFailure(val event: TelemetryEvent) :
  RuntimeException("Goal telemetry write failed for $event.")

private class RecordingGoalLifecycleTelemetryEmitter(
  private val failOn: Set<TelemetryEvent> = emptySet(),
) : GoalLifecycleTelemetryEmitter {
  val started: MutableList<GoalStartedRequest> = mutableListOf()
  val subtaskFinished: MutableList<GoalSubtaskFinishedRequest> = mutableListOf()
  val finished: MutableList<GoalFinishedRequest> = mutableListOf()
  val issueFinished: MutableList<GoalIssueFinishedRequest> = mutableListOf()

  override fun goalStarted(request: GoalStartedRequest) {
    if (TelemetryEvent.STARTED in failOn) throw TelemetryWriteFailure(TelemetryEvent.STARTED)
    started += request
  }

  override fun goalSubtaskFinished(request: GoalSubtaskFinishedRequest) {
    if (TelemetryEvent.SUBTASK_FINISHED in failOn) throw TelemetryWriteFailure(TelemetryEvent.SUBTASK_FINISHED)
    subtaskFinished += request
  }

  override fun goalFinished(request: GoalFinishedRequest) {
    if (TelemetryEvent.FINISHED in failOn) throw TelemetryWriteFailure(TelemetryEvent.FINISHED)
    finished += request
  }

  override fun goalIssueFinished(request: GoalIssueFinishedRequest) {
    issueFinished += request
  }
}

private fun DecompositionManifest.withCompletedSubtaskState(
  subtaskId: Int,
  workflowId: String,
  commitSha: String,
): DecompositionManifest =
  copy(
    status = "in_progress",
    currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "complete"),
    subtasks =
      subtasks.map { subtask ->
        if (subtask.id == subtaskId) {
          subtask.copy(
            status = "complete",
            workflowId = workflowId,
            commitSha = commitSha,
            lastResumableStep = "commit_push",
          )
        } else {
          subtask
        }
      },
  )

private fun DecompositionManifest.withBlockedSubtaskState(
  subtaskId: Int,
  workflowId: String,
  reason: String,
): DecompositionManifest =
  copy(
    status = "blocked",
    currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = subtaskId, action = "blocked"),
    subtasks =
      subtasks.map { subtask ->
        if (subtask.id == subtaskId) {
          subtask.copy(
            status = "blocked",
            workflowId = workflowId,
            blockedReason = reason,
            lastResumableStep = "validate",
          )
        } else {
          subtask
        }
      },
  )

private fun DecompositionManifest.withSkippedSubtaskState(subtaskId: Int): DecompositionManifest =
  copy(
    subtasks =
      subtasks.map { subtask ->
        if (subtask.id == subtaskId) subtask.copy(status = "skipped") else subtask
      },
  )
