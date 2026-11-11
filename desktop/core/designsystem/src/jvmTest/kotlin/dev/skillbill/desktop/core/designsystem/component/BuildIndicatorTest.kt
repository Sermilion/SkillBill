@file:OptIn(kotlin.time.ExperimentalTime::class)

package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
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
    exitCode = exitCode,
    finishedAt = finishedAt,
    previous = null,
  )

  @Test
  @OptIn(ExperimentalTestApi::class)
  fun `first build shows indeterminate activity then switches to a duration estimate`() = runTest {
    val ticker = UiTicker(object : Clock { override fun now(): Instant = start }, backgroundScope)
    runComposeUiTest {
      mainClock.autoAdvance = false
      val current = mutableStateOf(build(usualMs = null, elapsedMs = 95_000).copy(totalElapsedMs = 180_000))
      setContent {
        SkillBillTheme(reducedMotion = true) {
          CompositionLocalProvider(LocalUiTicker provides ticker) { BuildStrip(current.value) }
        }
      }

      onNodeWithText("Total build 3m 00s").assertExists()
      onNodeWithContentDescription("Build in progress").assert(SemanticsMatcher.expectValue(
        SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
      ))
      runOnIdle { current.value = current.value.copy(usualMs = 190_000) }
      mainClock.advanceTimeByFrame()
      onNodeWithContentDescription("Build in progress").assertDoesNotExist()
      onNodeWithContentDescription("Estimated build progress").assert(SemanticsMatcher.expectValue(
        SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(.5f, 0f..1f),
      ))
      runOnIdle { current.value = current.value.copy(state = SbBuildState.Passed) }
      mainClock.advanceTimeByFrame()
      onNodeWithContentDescription("Estimated build progress").assertDoesNotExist()
      onNodeWithContentDescription("Build in progress").assertDoesNotExist()
    }
  }

  @Test
  fun `slow tone changes only after elapsed time passes the usual duration`() {
    val build = build()
    val atBoundary = start + kotlin.time.Duration.parse("72.2s")
    val pastBoundary = start + kotlin.time.Duration.parse("73s")

    assertFalse(build.isSlow(atBoundary))
    assertEquals(SbBuildTone.None, build.tone(atBoundary))
    assertEquals("usually 3m 10s", build.subLine(atBoundary))
    assertTrue(build.isSlow(pastBoundary))
    assertEquals(SbBuildTone.Slow, build.tone(pastBoundary))
    assertEquals("0s over usual", build.subLine(pastBoundary))
  }

  @Test
  fun `sub line covers first run finished age and missing finish time`() {
    val now = start + kotlin.time.Duration.parse("60s")
    assertEquals("first run, no usual time yet", build(usualMs = null).subLine(now))
    assertNull(build(usualMs = null).barRatio(now))
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
    assertEquals("./gradlew check · Android · usually 3m 10s", build().chipTooltip(now))
  }

  @Test
  fun `total build time advances only while a command is active and survives command changes`() {
    val now = start + kotlin.time.Duration.parse("10s")
    val current = build().copy(totalElapsedMs = 120_000, totalElapsedAsOf = start)
    assertEquals(Plain("Total build 2m 10s"), current.metaSegments(now, "10:00").first())
    val finished = current.copy(state = SbBuildState.Passed, totalElapsedMs = 130_000, totalElapsedAsOf = null)
    assertEquals(Plain("Total build 2m 10s"), finished.metaSegments(now + kotlin.time.Duration.parse("60s"), "10:00").first())
  }

  @Test
  fun `exit metadata retains zero and excludes running or unknown exits`() {
    val now = start
    assertEquals(listOf(Plain("exit 0")), build(SbBuildState.Passed, exitCode = 0).metaSegments(now, "10:00").takeLast(1))
    assertTrue(build(exitCode = 2).metaSegments(now, "10:00").none { it == Plain("exit 2") })
    assertTrue(build(SbBuildState.Failed).metaSegments(now, "10:00").none { it is Plain && it.text.startsWith("exit ") })
  }
}
