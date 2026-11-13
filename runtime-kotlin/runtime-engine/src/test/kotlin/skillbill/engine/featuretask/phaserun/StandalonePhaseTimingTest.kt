package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.ports.agentrun.activity.model.AgentRunActivity
import skillbill.ports.agentrun.activity.model.AgentRunActivityKind
import skillbill.ports.agentrun.activity.model.AgentRunActivityState
import skillbill.ports.agentrun.model.AgentRunActivityOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StandalonePhaseTimingTest {
  @Test
  fun `monitor persists reported model and clears it when the next repair has not reported`() {
    val home = Files.createTempDirectory("monitor-model")
    val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
    val database = phaseRunDatabase(home, clock)
    try {
      val publisher =
        testStandalonePhasePublisher(database, clock, NoopRuntimeDiagnostics)
          .forPhase(home, "monitor", "monitor-model", StandalonePhaseLaunch("codex"))
      publisher.publishActivity("monitor", "Waiting for CI")

      fun model() = database.read { it.standalonePhaseStatuses.readByInvocation("monitor-model") }?.reportedModel
      assertNull(model())
      publisher.reportModel("provider-model")
      assertEquals("provider-model", model())
      publisher.publishActivity("monitor_fix", "Fixing CI")
      assertEquals("provider-model", model())
      publisher.reportModel(null)
      assertNull(model())
      publisher.reportModel("second-model")
      publisher.settle(PhaseRunResult.Completed("monitor-model", emptyList(), null, null))
      assertEquals("second-model", model())
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  @Test
  fun `goal planning records active time before subtasks and excludes a paused interval`() {
    val home = Files.createTempDirectory("goal-plan-clock")
    val start = Instant.parse("2026-10-25T10:00:00Z")
    val clock = PlanningClock(start)
    val database = phaseRunDatabase(home, clock)
    try {
      val publisher =
        testStandalonePhasePublisher(database, clock, NoopRuntimeDiagnostics).forPlan(
          home,
          "APP-123",
          "plan",
          StandalonePhaseLaunch(scope = IdeStatusLaunchScope.GOAL),
        )
      publisher.emit(FeatureTaskRuntimeRunEvent.RunStarted("plan-workflow", "MEDIUM"))
      clock.now = start.plusSeconds(30)
      publisher.emit(FeatureTaskRuntimeRunEvent.PhaseStarted("plan-workflow", "preplan", "codex", 1, false))
      val running = database.read { it.standalonePhaseStatuses.latestStandaloneExecution("plan-workflow") }
      assertEquals(30_000, assertNotNull(running).activeDurationMs)
      assertEquals(clock.now, running.activeDurationAsOf)
      assertEquals(IdeStatusLaunchScope.GOAL, running.launchScope)

      clock.now = start.plusSeconds(40)
      publisher.emit(FeatureTaskRuntimeRunEvent.PhasePaused("plan-workflow", "preplan", "codex", 1, "usage limit"))
      clock.now = start.plusSeconds(100)
      publisher.emit(FeatureTaskRuntimeRunEvent.PhaseStarted("plan-workflow", "preplan", "codex", 2, true))
      clock.now = start.plusSeconds(120)
      publisher.settlePlan("completed", "plan-workflow")

      val settled = database.read { it.standalonePhaseStatuses.latestStandaloneExecution("plan-workflow") }
      assertEquals(60_000, assertNotNull(settled).activeDurationMs)
      assertNull(settled.activeDurationAsOf)
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  @Test
  fun `goal planning captures agent output for the plan detail`() {
    val home = Files.createTempDirectory("goal-plan-output")
    val start = Instant.parse("2026-10-25T10:00:00Z")
    val clock = PlanningClock(start)
    val database = phaseRunDatabase(home, clock)
    try {
      val publisher =
        testStandalonePhasePublisher(database, clock, NoopRuntimeDiagnostics).forPlan(
          home,
          "APP-123",
          "plan",
          StandalonePhaseLaunch(scope = IdeStatusLaunchScope.GOAL),
        )
      publisher.emit(FeatureTaskRuntimeRunEvent.RunStarted("plan-workflow", "MEDIUM"))
      publisher.planOutputSink.write(AgentRunOutputStream.STDOUT, "Reading the intake\nSubtask 2: writing spec\n")
      publisher.closeOutputCapture()

      val record =
        assertNotNull(database.read { it.standalonePhaseStatuses.latestStandaloneExecution("plan-workflow") })
      val output = assertNotNull(database.read { it.standalonePhaseStatuses.readOutput(record.executionId) })
      assertEquals(listOf("Reading the intake", "Subtask 2: writing spec"), output.tail)
      assertEquals(start, output.latestLineAt)
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  @Test
  fun `structured tool capture keeps phase attribution raw data and observed duration`() {
    val home = Files.createTempDirectory("plan-activity-capture")
    val start = Instant.parse("2026-11-06T10:00:00Z")
    val clock = PlanningClock(start)
    val database = phaseRunDatabase(home, clock)
    try {
      val publisher =
        testStandalonePhasePublisher(
          database,
          clock,
          NoopRuntimeDiagnostics,
        ).forPlan(home, "APP-123", "plan", StandalonePhaseLaunch())
      publisher.emit(FeatureTaskRuntimeRunEvent.PhaseStarted("plan-workflow", "preplan", "claude", 1, false))
      val sink = publisher.planOutputSink as AgentRunActivityOutputSink
      val tool =
        AgentRunActivity(
          "read-one",
          AgentRunActivityKind.READ,
          "/repo/a.kt",
          "claude",
          "/repo/a.kt",
          AgentRunActivityState.STARTED,
        )
      sink.activity(tool)
      sink.raw(AgentRunOutputStream.STDOUT, "{\"type\":\"assistant\"}")
      clock.now = start.plusMillis(100)
      sink.activity(tool.copy(state = AgentRunActivityState.COMPLETED))
      publisher.closeOutputCapture()
      val record =
        assertNotNull(database.read { it.standalonePhaseStatuses.latestStandaloneExecution("plan-workflow") })
      val activity =
        assertNotNull(
          database.read {
            it.standalonePhaseStatuses.readOutput(record.executionId)
          },
        ).activities.single()
      assertEquals("preplan", activity.phaseId)
      assertEquals(start, activity.at)
      assertEquals(100L, activity.event.durationMs)
      val raw = database.read { it.standalonePhaseStatuses.readOutputLines(record.executionId, null, 20) }.single()
      assertEquals(true, raw.isRaw)
      assertEquals("{\"type\":\"assistant\"}", raw.text)
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  private class PlanningClock(var now: Instant) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
  }
}
