package skillbill.engine.goalrunner.execution.core

import skillbill.application.telemetry.lifecycle.GoalLifecycleTelemetryEmitter
import skillbill.application.telemetry.lifecycle.toRecord
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
import skillbill.engine.goalrunner.launchFacts
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerEventSink
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.workflow.decomposition.withWorkflowId
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class GoalModeAttributionUnitTest {
  @Test
  fun `GoalStartedRequest mode field passes through toRecord`() {
    val request =
      GoalStartedRequest(
        issueKey = "SKILL-92",
        featureName = "test",
        workflowId = "wf-1",
        subtaskTotal = 1,
        resumed = false,
        startedAt = "2026-06-23T10:00:00Z",
        mode = "prose",
      )
    val record = request.toRecord()
    assertEquals("prose", record.mode)
    assertEquals("running", record.status)
  }

  @Test
  fun `goal telemetry records normalize safe issue keys and reject control characters`() {
    val request =
      GoalStartedRequest(
        issueKey = "  SKILL-92  ",
        featureName = "test",
        workflowId = "wf-1",
        subtaskTotal = 1,
        resumed = false,
        startedAt = "2026-06-23T10:00:00Z",
        mode = "prose",
      )

    assertEquals("SKILL-92", request.toRecord().issueKey)
    assertFailsWith<IllegalArgumentException> { request.copy(issueKey = "SKILL-92\nspoofed").toRecord() }
  }

  @Test
  fun `GoalFinishedRequest mode field passes through toRecord`() {
    val request =
      GoalFinishedRequest(
        issueKey = "SKILL-92",
        workflowId = "wf-1",
        status = "completed",
        startedAt = "2026-06-23T10:00:00Z",
        finishedAt = "2026-06-23T11:00:00Z",
        durationMs = 3_600_000,
        subtasksComplete = 1,
        subtasksBlocked = 0,
        subtasksSkipped = 0,
        mode = "runtime",
      )
    val record = request.toRecord()
    assertEquals("runtime", record.mode)
  }

  @Test
  fun `GoalRunnerTelemetryEmitter stamps mode=runtime on goalStarted`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher = completingLauncher(store, outcomes)
    val telemetry = ModeCapturingTelemetryEmitter()
    val runner = goalRunner(store, launcher, outcomes, telemetry)

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals("runtime", telemetry.started.single().mode)
    assertEquals("running", telemetry.started.single().status)
  }

  @Test
  fun `GoalRunnerTelemetryEmitter stamps mode=runtime on goalFinished`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher = completingLauncher(store, outcomes)
    val telemetry = ModeCapturingTelemetryEmitter()
    val runner = goalRunner(store, launcher, outcomes, telemetry)

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals("runtime", telemetry.finished.single().mode)
  }

  private fun goalRunner(
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
        clock = Clock.fixed(Instant.parse("2026-06-23T10:00:00Z"), ZoneOffset.UTC),
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

  private fun runRequest(): GoalRunnerRunRequest =
    GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      invokedAgentId = "claude",
      eventSink = GoalRunnerEventSink {},
    )
}

private class ModeCapturingTelemetryEmitter : GoalLifecycleTelemetryEmitter {
  val started: MutableList<GoalStartedRequest> = mutableListOf()
  val finished: MutableList<GoalFinishedRequest> = mutableListOf()

  override fun goalStarted(request: GoalStartedRequest) {
    started += request
  }

  override fun goalSubtaskFinished(request: GoalSubtaskFinishedRequest) = Unit

  override fun goalFinished(request: GoalFinishedRequest) {
    finished += request
  }

  override fun goalIssueFinished(request: GoalIssueFinishedRequest) = Unit
}
