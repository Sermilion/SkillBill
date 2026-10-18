package dev.skillbill.tray.feature.detail

import dev.skillbill.tray.core.domain.model.ExecutionScope
import dev.skillbill.tray.core.domain.model.Freshness
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.ReasonRestoration
import dev.skillbill.tray.core.domain.model.WorkFamily
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
  fun `a standalone phase is titled by its phase and a goal by its kind`() {
    val phase = untitled().let { it.copy(item = it.item.copy(scope = ExecutionScope.STANDALONE_PHASE, phaseId = "validation")) }

    assertEquals("Validation · SKILL-phase", detailTitle(phase))
    assertEquals("Goal · SKILL-phase", detailTitle(untitled()))
    assertEquals("Goal", detailTitle(untitled(issueKey = null)))
  }

  @Test
  fun `a title ignores lifecycle, failure reasons and structured phase output`() {
    Lifecycle.entries.forEach { lifecycle ->
      val phase = workItem("phase", lifecycle, WorkFamily.FEATURE_VERIFY, issueKey = "SKILL-phase").copy(
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
}
