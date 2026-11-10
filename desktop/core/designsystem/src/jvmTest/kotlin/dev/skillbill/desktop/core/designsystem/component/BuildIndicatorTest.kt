@file:OptIn(kotlin.time.ExperimentalTime::class)

package dev.skillbill.desktop.core.designsystem.component

import dev.skillbill.desktop.core.designsystem.component.SbBuildMeta.Plain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class BuildIndicatorTest {
  private val start = Instant.fromEpochMilliseconds(1_000_000)

  private fun build(
    state: SbBuildState = SbBuildState.Running,
    elapsedMs: Long? = 117_800,
    elapsedAsOf: Instant? = start,
    usualMs: Long? = 190_000,
    sampleCount: Int? = 20,
    exitCode: Int? = null,
    finishedAt: Instant? = null,
  ) = SbBuild(
    state = state,
    command = "./gradlew check",
    shortCommand = "gradlew check",
    tool = "Gradle",
    projectKind = "Android",
    pid = 42,
    startedAt = start,
    elapsedMs = elapsedMs,
    elapsedAsOf = elapsedAsOf,
    usualMs = usualMs,
    sampleCount = sampleCount,
    exitCode = exitCode,
    finishedAt = finishedAt,
    previous = null,
  )

  @Test
  fun `slow tone changes only after elapsed time passes the usual duration`() {
    val build = build()
    val atBoundary = start + kotlin.time.Duration.parse("72.2s")
    val pastBoundary = start + kotlin.time.Duration.parse("73s")

    assertFalse(build.isSlow(atBoundary))
    assertEquals(SbBuildTone.None, build.tone(atBoundary))
    assertEquals("average 3m 10s over 20 runs", build.subLine(atBoundary))
    assertTrue(build.isSlow(pastBoundary))
    assertEquals(SbBuildTone.Slow, build.tone(pastBoundary))
    assertEquals("average 3m 10s over 20 runs", build.subLine(pastBoundary))
  }

  @Test
  fun `sub line covers first run finished age and missing finish time`() {
    val now = start + kotlin.time.Duration.parse("60s")
    assertEquals("no command history for this project yet", build(usualMs = null).subLine(now))
    assertNull(build(usualMs = null).barRatio(now))
    assertEquals("average 3m 10s over 1 run", build(sampleCount = 1).subLine(now))
    assertEquals("average 3m 10s", build(sampleCount = null).subLine(now))
    assertEquals("finished 1m ago", build(SbBuildState.Failed, elapsedMs = null, finishedAt = start).subLine(now))
    assertNull(build(SbBuildState.Passed, elapsedMs = null).subLine(now))
  }

  @Test
  fun `bar ratio is proportional capped and absent after completion`() {
    val half = build(elapsedMs = 95_000).barRatio(start)
    val over = build(elapsedMs = 250_000).barRatio(start)
    val completed = build(SbBuildState.Passed, elapsedMs = 190_000).barRatio(start)

    assertEquals(.5f, half)
    assertEquals(1f, over)
    assertNull(completed)
  }

  @Test
  fun `tooltips include failure exit and usual duration without nullable exit text`() {
    val now = start
    assertEquals(
      "./gradlew check exited 2 after 1m 05s",
      build(SbBuildState.Failed, elapsedMs = 65_000, exitCode = 2).chipTooltip(now),
    )
    assertEquals(
      "./gradlew check failed after 1m 05s",
      build(SbBuildState.Failed, elapsedMs = 65_000).chipTooltip(now),
    )
    assertEquals("./gradlew check · Android · average 3m 10s over 20 runs", build().chipTooltip(now))
  }

  @Test
  fun `exit metadata retains zero and excludes running or unknown exits`() {
    val now = start
    assertEquals(listOf(Plain("exit 0")), build(SbBuildState.Passed, exitCode = 0).metaSegments(now, "10:00").takeLast(1))
    assertTrue(build(exitCode = 2).metaSegments(now, "10:00").none { it == Plain("exit 2") })
    assertTrue(build(SbBuildState.Failed).metaSegments(now, "10:00").none { it is Plain && it.text.startsWith("exit ") })
  }
}
