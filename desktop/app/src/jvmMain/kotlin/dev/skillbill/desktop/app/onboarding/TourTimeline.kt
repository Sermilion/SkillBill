package dev.skillbill.desktop.app.onboarding

internal data class TourBeat<S>(val apply: (S) -> S)

/**
 * An ordered list of manually advanced scene states. Beat 0 applies the first beat over [initial];
 * nothing here runs on a clock — the tour's Next and Back move the index.
 */
internal class TourTimeline<S>(val initial: S, val beats: List<TourBeat<S>>) {
  val beatCount: Int get() = beats.size

  fun stateAt(beat: Int): S {
    if (beats.isEmpty()) return initial
    return beats.take(beat.coerceIn(0, beats.lastIndex) + 1).fold(initial) { state, b -> b.apply(state) }
  }

  fun finalState(): S = stateAt(beats.lastIndex)
}
