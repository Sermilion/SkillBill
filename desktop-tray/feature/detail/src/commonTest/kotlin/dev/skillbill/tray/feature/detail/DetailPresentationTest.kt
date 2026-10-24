package dev.skillbill.tray.feature.detail

import dev.skillbill.tray.core.domain.model.ExecutionInstance
import dev.skillbill.tray.core.domain.model.ExecutionScope
import dev.skillbill.tray.core.domain.model.Freshness
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.PlanRunReport
import dev.skillbill.tray.core.domain.model.PlannedSubtask
import dev.skillbill.tray.core.domain.model.QaVerdict
import dev.skillbill.tray.core.domain.model.ReasonRestoration
import dev.skillbill.tray.core.domain.model.WorkFamily
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.testing.detail
import dev.skillbill.tray.core.testing.fixtureInstant
import dev.skillbill.tray.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DetailPresentationTest {
  private fun untitled(issueKey: String? = "SKILL-phase") = detail(workItem("a", issueKey = issueKey)).copy(featureTitle = null)

  @Test
  fun `each run in a qa phase history reads its own stored verdict`() {
    val latest = workItem("qa-run", Lifecycle.TERMINAL, family = null, issueKey = null)
      .copy(scope = ExecutionScope.STANDALONE_PHASE, phaseId = "qa", executionId = "execution-latest", qaVerdict = QaVerdict.ATTEMPTED)
    fun run(id: String, state: String, verdict: QaVerdict?) =
      ExecutionInstance("invocation-$id", id, state, fixtureInstant, fixtureInstant, fixtureInstant, null, qaVerdict = verdict)

    assertEquals(
      listOf("Incomplete", "Findings", "Skipped", "Complete", "Failed"),
      listOf(
        executionOutcomeLabel(latest, run("execution-latest", "terminal", QaVerdict.ATTEMPTED)),
        executionOutcomeLabel(latest, run("execution-older-1", "terminal", QaVerdict.FINDINGS)),
        executionOutcomeLabel(latest, run("execution-older-2", "terminal", QaVerdict.SKIPPED)),
        executionOutcomeLabel(latest, run("execution-older-3", "terminal", null)),
        executionOutcomeLabel(latest, run("execution-older-4", "failed", QaVerdict.ATTEMPTED)),
      ),
    )
  }

  @Test
  fun `a standalone phase is titled by its phase and a goal by its kind`() {
    val phase = untitled().let { it.copy(item = it.item.copy(scope = ExecutionScope.STANDALONE_PHASE, phaseId = "validation")) }

    assertEquals("Validation · SKILL-phase", detailTitle(phase))
    assertEquals("Goal · SKILL-phase", detailTitle(untitled()))
    assertEquals("Goal", detailTitle(untitled(issueKey = null)))
  }

  @Test
  fun `a title ignores lifecycle, failure reasons and structured phase output`() {
    Lifecycle.entries.forEach { lifecycle ->
      val phase = workItem("phase", lifecycle, family = null, issueKey = "SKILL-phase").copy(
        scope = ExecutionScope.STANDALONE_PHASE,
        phaseId = "validation",
        summary = "{\"status\":\"failed\",\"summary\":\"Build failed because a command exited\"}",
        pauseReason = "Blocked because credentials are missing",
      )
      val titled = detail(phase).copy(featureTitle = null)

      assertEquals("Validation · SKILL-phase", detailTitle(titled))
      assertEquals("Named feature title", detailTitle(titled.copy(featureTitle = "Named feature title")))
    }
  }

  @Test
  fun `a feature title is shown as written unless it is a bare slug`() {
    assertEquals("Readable title", detailTitle(untitled().copy(featureTitle = "readable-title")))
    assertEquals("Already Readable", detailTitle(untitled().copy(featureTitle = "Already Readable")))
  }

  @Test
  fun `a finished item reports its total run time and a running goal its live clock`() {
    val now = fixtureInstant.plus(30.seconds)
    val running = workItem("a").copy(activeDurationMs = 40_000, activeDurationAsOf = fixtureInstant)
    val finished = running.copy(lifecycle = Lifecycle.TERMINAL, finishedAt = fixtureInstant)

    assertEquals("Total run time", detailDuration(finished, now, stale = false).label)
    assertEquals("40s", detailDuration(finished, now, stale = false).value)
    assertEquals("Goal active", detailDuration(running, now, stale = false).label)
    assertEquals("1m 10s", detailDuration(running, now, stale = false).value)
  }

  @Test
  fun `finished goals and phases show recorded run time without counting idle gaps`() {
    val finish = Instant.parse("2026-10-13T10:00:00Z")
    listOf(ExecutionScope.WORKFLOW, ExecutionScope.STANDALONE_PHASE).forEach { scope ->
      val work = workItem("finished", Lifecycle.TERMINAL).copy(
        scope = scope,
        freshness = Freshness.STALE,
        startedAt = finish - 3660.seconds,
        finishedAt = finish,
        updatedAt = finish + 600.seconds,
        activeDurationMs = 120_000,
      )

      val metric = detailDuration(work, finish + 3600.seconds, stale = true)

      assertEquals("Total run time", metric.label)
      assertEquals("2m 0s", metric.value)
      assertTrue(metric.note.startsWith("finished "))
      assertEquals(metric, detailDuration(work, finish + 7200.seconds, stale = true, frozen = 90_000))
      assertEquals("not available", detailDuration(work.copy(activeDurationMs = null), finish, stale = true).value)
    }
  }

  @Test
  fun `a stale read freezes the running clock`() {
    val running = workItem("a").copy(activeDurationMs = 40_000, activeDurationAsOf = fixtureInstant)

    assertEquals("40s", detailDuration(running, fixtureInstant.plus(30.seconds), stale = true).value)
  }

  @Test
  fun `a standalone phase reports its elapsed time`() {
    val phase = workItem("a").copy(scope = ExecutionScope.STANDALONE_PHASE, startedAt = fixtureInstant, updatedAt = fixtureInstant)

    val duration = detailDuration(phase, fixtureInstant.plus(30.seconds), stale = false)

    assertEquals("Phase elapsed", duration.label)
    assertEquals("30s", duration.value)
  }

  @Test
  fun `only an unrecoverable reason carries a note about being shortened`() {
    assertNull(restorationNote(null))
    assertNull(restorationNote(ReasonRestoration.EXACT))
    assertNull(restorationNote(ReasonRestoration.RESTORED))
    listOf(ReasonRestoration.MISSING, ReasonRestoration.MISSING_CONTENT, ReasonRestoration.AMBIGUOUS, ReasonRestoration.BUDGET_EXCEEDED)
      .forEach { assertNotNull(restorationNote(it), "$it") }
  }

  @Test
  fun `goal finish marks the steps before monitor_fix done and keeps highlighting through repair`() {
    val pipeline = listOf("pr", "monitor", "monitor_fix")
    fun states(item: WorkItem) = pipelineSteps(item, pipeline).map { it.state }

    assertEquals(
      listOf(PipelineStepState.COMPLETE, PipelineStepState.COMPLETE, PipelineStepState.ACTIVE),
      states(workItem("a").copy(currentStepId = "monitor_fix")),
    )
    assertEquals(
      List(3) { PipelineStepState.PENDING },
      states(workItem("a").copy(currentStepId = "monitor", currentSubtaskId = "2")),
    )
    assertEquals(
      List(3) { PipelineStepState.COMPLETE },
      states(workItem("a", lifecycle = Lifecycle.TERMINAL).copy(currentStepId = "complete")),
    )
  }

  @Test
  fun `a running plan marks preplan and plan done and highlights plan_subtasks`() {
    val item = workItem("plan-run", family = null).copy(scope = ExecutionScope.STANDALONE_PHASE, currentStepId = "plan_subtasks")

    assertEquals(
      listOf(PipelineStepState.COMPLETE, PipelineStepState.COMPLETE, PipelineStepState.ACTIVE),
      pipelineSteps(item, listOf("preplan", "plan", "plan_subtasks")).map { it.state },
    )
  }

  @Test
  fun `a plan run counts the subtask specs its live report lists and says nothing before the report arrives`() {
    val item = workItem("plan-run", family = null).copy(scope = ExecutionScope.STANDALONE_PHASE, invocationId = "plan-current")
    fun run(invocationId: String, subtasks: Int?) = ExecutionInstance(
      invocationId, "execution-$invocationId", "active", fixtureInstant, fixtureInstant, null, null,
      planReport = subtasks?.let { count ->
        PlanRunReport("feature", "/specs/spec.md", List(count) { PlannedSubtask(it + 1, "part ${it + 1}", "/specs/s$it.md", emptyList()) })
      },
    )

    assertEquals("", standalonePlanNote(detail(item).copy(executionHistory = listOf(run("plan-current", null), run("plan-older", 4)))))
    assertEquals("6 subtask specs", standalonePlanNote(detail(item).copy(executionHistory = listOf(run("plan-older", 4), run("plan-current", 6)))))
    assertEquals("1 subtask spec", standalonePlanNote(detail(item).copy(executionHistory = listOf(run("plan-current", 1)))))
  }
}
