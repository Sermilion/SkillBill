package dev.skillbill.desktop.feature.history

import dev.skillbill.desktop.core.ui.input.ScreenKey

/** The shell's screen keys as History handles them; History has no run controls, and the filter key focuses the filter in the screen. */
internal fun historyKeyAction(key: ScreenKey): HistoryAction? = when (key) {
  ScreenKey.Next -> HistoryAction.MoveFocus(1)
  ScreenKey.Previous -> HistoryAction.MoveFocus(-1)
  ScreenKey.Open -> HistoryAction.OpenFocused
  ScreenKey.FocusFilter, ScreenKey.PauseAfterSubtask, ScreenKey.PauseNow, ScreenKey.Resume -> null
}
