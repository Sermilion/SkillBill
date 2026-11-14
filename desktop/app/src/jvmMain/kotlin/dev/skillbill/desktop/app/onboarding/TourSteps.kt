package dev.skillbill.desktop.app.onboarding

import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.ui.pane.MOD_KEY

internal data class TourStepCopy(val title: String, val body: String)

internal val TOUR_STEP_COPY = listOf(
  TourStepCopy(
    "Ask in plain words",
    "Open New task and describe what you want changed, the way you'd tell a teammate. " +
      "Sona asks about anything unclear, then offers a start button. Nothing runs until you click it. " +
      "Started work shows up here on the Work board.",
  ),
  TourStepCopy(
    "Three kinds of runs",
    "A goal is a whole feature: plan, code, review, checks and a pull request, split into small commits. " +
      "A phase is one of those stages run on its own, like just a review or just your checks. " +
      "An operation is a one-off job, like verifying a ticket's acceptance criteria. All three appear on this board.",
  ),
  TourStepCopy(
    "Watch and steer",
    "Open a run to see each stage live. Big goals split into subtasks with one commit each. " +
      "Press P to pause after the current one and R to resume. Progress is saved, so a closed laptop doesn't lose work.",
  ),
  TourStepCopy(
    "Review and ship",
    "Comment on the diff and the agent fixes it in a new commit. " +
      "When everything passes, Sona opens the pull request and watches CI. You decide when it merges.",
  ),
)

internal val TOUR_KEY_GRID = listOf(
  "$MOD_KEY K" to "Command menu", "?" to "All shortcuts", "G N" to "New task",
  "G W" to "Work", "⌃ `" to "Terminal", "$MOD_KEY I" to "Details pane",
)

/** A single-beat timeline that sits on [state]; merged steps have nothing to step through. */
private fun <S> staticTimeline(state: S): TourTimeline<S> = TourTimeline(state, listOf(TourBeat { it }))

internal fun tourStartTimeline() = staticTimeline(tourWorkTimeline().finalState())

internal fun tourKindsTimeline() = staticTimeline(
  TourBoardState(
    newRow = TourFixture.newGoalRowActive,
    overlay = StageOverlay(
      spotlight = Spotlight(TourTarget.BoardLanes, TourFixture.tipRunKinds, TipSide.Bottom, 0.dp, 2.dp),
    ),
  ),
)

internal fun tourSteerTimeline() = staticTimeline(
  tourControlTimeline().finalState().copy(
    overlay = StageOverlay(
      spotlight = Spotlight(TourTarget.Pipeline, TourFixture.tipPipeline, TipSide.Bottom, pad = 2.dp),
    ),
  ),
)

internal fun tourShipTimeline() = staticTimeline(tourFinishTimeline().finalState())

/** The merged staged steps each hold a single beat. */
internal fun tourBeatCount(step: Int): Int = when (step) {
  1 -> tourStartTimeline()
  2 -> tourKindsTimeline()
  3 -> tourSteerTimeline()
  4 -> tourShipTimeline()
  else -> null
}?.beatCount ?: 1
