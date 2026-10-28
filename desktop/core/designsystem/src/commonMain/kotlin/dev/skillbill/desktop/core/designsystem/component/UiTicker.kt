package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

private const val TICK_MS = 1_000L

@OptIn(kotlin.time.ExperimentalTime::class)
/** Confined to the composition thread owned by its window. */
class UiTicker(private val clock: Clock, private val scope: CoroutineScope) {
  private val nowState = mutableStateOf(clock.now())
  val now: State<Instant> get() = nowState
  private var retains = 0
  private var job: Job? = null
  internal val isTicking: Boolean get() = job?.isActive == true

  fun retain() {
    retains += 1
    if (retains == 1) {
      nowState.value = clock.now()
      job = scope.launch {
        while (true) {
          delay(TICK_MS)
          nowState.value = clock.now()
        }
      }
    }
  }

  fun release() {
    check(retains > 0) { "UiTicker released without a retain" }
    retains -= 1
    if (retains == 0) {
      job?.cancel()
      job = null
    }
  }
}

val LocalUiTicker = staticCompositionLocalOf<UiTicker> { error("LocalUiTicker is provided by SkillBillTheme") }

@Composable
@OptIn(kotlin.time.ExperimentalTime::class)
fun rememberUiTicker(clock: Clock = Clock.System): UiTicker {
  val scope = rememberCoroutineScope()
  return remember(clock, scope) { UiTicker(clock, scope) }
}
