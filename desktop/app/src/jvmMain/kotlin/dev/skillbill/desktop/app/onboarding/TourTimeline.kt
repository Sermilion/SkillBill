package dev.skillbill.desktop.app.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay

internal data class TourCue<S>(val atMs: Int, val apply: (S) -> S)

internal class TourTimeline<S>(val initial: S, val cues: List<TourCue<S>>, val endMs: Int) {
  fun finalState(): S = cues.fold(initial) { state, cue -> cue.apply(state) }

  suspend fun play(reducedMotion: Boolean, emit: (S) -> Unit) {
    if (reducedMotion) {
      emit(finalState())
      return
    }
    emit(initial)
    var elapsed = 0
    var current = initial
    cues.forEach { cue ->
      delay((cue.atMs - elapsed).coerceAtLeast(0).toLong())
      current = cue.apply(current)
      emit(current)
      elapsed = cue.atMs
    }
    delay((endMs - elapsed).coerceAtLeast(0).toLong())
  }
}

@Composable
internal fun <S> rememberTourTimeline(
  step: Int,
  replayToken: Int,
  timeline: TourTimeline<S>,
  reducedMotion: Boolean,
  onEnd: () -> Unit,
): State<S> {
  val state = remember(step, replayToken, timeline, reducedMotion) {
    mutableStateOf(if (reducedMotion) timeline.finalState() else timeline.initial)
  }
  val end by rememberUpdatedState(onEnd)
  LaunchedEffect(step, replayToken, timeline, reducedMotion) {
    timeline.play(reducedMotion) { state.value = it }
    end()
  }
  return state
}
