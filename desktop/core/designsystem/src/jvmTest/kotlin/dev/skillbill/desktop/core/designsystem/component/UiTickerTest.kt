package dev.skillbill.desktop.core.designsystem.component

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(kotlin.time.ExperimentalTime::class)
class UiTickerTest {
  @Test
  fun `ticker stops when its final clock releases`() = runTest {
    val fakeClock = object : Clock {
      override fun now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime)
    }
    val ticker = UiTicker(fakeClock, backgroundScope)
    ticker.retain()
    ticker.retain()
    advanceTimeBy(3_000)
    runCurrent()
    assertEquals(Instant.fromEpochMilliseconds(3_000), ticker.now.value)
    ticker.release()
    assertTrue(ticker.isTicking)
    ticker.release()
    assertFalse(ticker.isTicking)
    advanceTimeBy(5_000)
    runCurrent()
    assertEquals(Instant.fromEpochMilliseconds(3_000), ticker.now.value)
  }
}
