package dev.skillbill.desktop.core.designsystem.component

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.width
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(kotlin.time.ExperimentalTime::class)
class UiTickerTest {
  @Test
  @OptIn(ExperimentalTestApi::class)
  fun `removing the final live clock from composition releases the ticker`() = runComposeUiTest {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val ticker = UiTicker(object : Clock {
      override fun now(): Instant = Instant.fromEpochMilliseconds(0)
    }, scope)
    val visible = mutableStateOf(true)
    setContent {
      SkillBillTheme(reducedMotion = false) {
        CompositionLocalProvider(LocalUiTicker provides ticker) {
          if (visible.value) SbClock(0, Instant.fromEpochMilliseconds(0), live = true)
        }
      }
    }

    runOnIdle { assertTrue(ticker.isTicking) }
    runOnIdle { visible.value = false }
    runOnIdle { assertFalse(ticker.isTicking) }
    scope.cancel()
  }

  @Test
  @OptIn(ExperimentalTestApi::class)
  fun `field validation text wraps inside a narrow settings column`() = runComposeUiTest {
    val message = "Clear this field to use the default database path."
    setContent {
      SkillBillTheme(reducedMotion = false) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(90.dp)) {
          SbField("Database path", error = message) {}
        }
      }
    }

    onNodeWithText(message).assertHeightIsAtLeast(30.dp)
  }

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
