package dev.skillbill.desktop.app.onboarding

import dev.skillbill.desktop.core.ui.pane.MOD_KEY

internal data class TourStepCopy(val title: String, val body: String)

internal val TOUR_STEP_COPY = listOf(
  TourStepCopy("One sidebar, five places", "New task is where work starts. Work shows everything that is running. History, Plans and Settings cover the rest. Each one has a two-key shortcut."),
  TourStepCopy("Start with a conversation", "Describe what you want. The agent reads the repo, asks what it needs to know, and proposes a goal. Nothing runs until you start it."),
  TourStepCopy("Work is your board", "Every run lands here, grouped by what it needs from you. Anything waiting on a decision sits in Needs you, at the top."),
  TourStepCopy("Watch a goal break down", "Sona splits the goal into subtasks. Each one goes through the same steps and ends in its own commit, so you can follow or rewind any of it."),
  TourStepCopy("You stay in control", "Press P to pause after the current subtask, or Shift P to stop right away. A paused run moves to Needs you. Press R to pick it back up."),
  TourStepCopy("Review like a teammate", "Open the changes, comment on any line, and add your review. The agent picks up the comments and fixes them in a new commit."),
  TourStepCopy("From goal to pull request", "When the last subtask lands, Sona checks the whole goal against the spec, runs QA and the project checks, opens the PR and watches CI until it passes."),
  TourStepCopy("Keys for everything", "The command menu reaches every action from anywhere. Press ? for the full list of shortcuts."),
)

internal val TOUR_KEY_GRID = listOf(
  "$MOD_KEY K" to "Command menu", "?" to "All shortcuts", "G N" to "New task",
  "G W" to "Work", "⌃ `" to "Terminal", "$MOD_KEY I" to "Details pane",
)

/** How many beats each staged step holds; hero steps have a single static beat. */
internal fun tourBeatCount(step: Int): Int = when (step) {
  1 -> tourSidebarTimeline()
  2 -> tourChatTimeline()
  3 -> tourWorkTimeline()
  4 -> tourRunDetailTimeline()
  5 -> tourControlTimeline()
  6 -> tourReviewTimeline()
  7 -> tourFinishTimeline()
  8 -> tourCommandsTimeline()
  else -> null
}?.beatCount ?: 1
