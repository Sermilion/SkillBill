package dev.skillbill.desktop.feature.work

import dev.skillbill.desktop.core.ui.input.ScreenKey

/** The shell's screen keys as Work handles them; the filter key focuses the filter in the screen itself, so it maps to none. */
internal fun workKeyAction(key: ScreenKey): WorkAction? = when (key) {
  ScreenKey.Next -> WorkAction.MoveFocus(1)
  ScreenKey.Previous -> WorkAction.MoveFocus(-1)
  ScreenKey.Open -> WorkAction.OpenFocused
  ScreenKey.PauseAfterSubtask -> WorkAction.FocusedKey(RowKey.Pause)
  ScreenKey.PauseNow -> WorkAction.FocusedKey(RowKey.PauseNow)
  ScreenKey.Resume -> WorkAction.FocusedKey(RowKey.Launch)
  ScreenKey.FocusFilter -> null
}
