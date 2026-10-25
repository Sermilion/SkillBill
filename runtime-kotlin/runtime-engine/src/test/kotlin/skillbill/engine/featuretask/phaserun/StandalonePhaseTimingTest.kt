package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.idestatus.model.IdeStatusLaunchScope
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

  private class PlanningClock(var now: Instant) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
  }
}
