package dev.skillbill.desktop.app.onboarding

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

internal enum class TourKeyAction { Next, Back, Escape, PassToFocus, Swallow }

internal fun tourKeyAction(key: Key, keyDown: Boolean, modified: Boolean): TourKeyAction {
  if (key == Key.Enter || key == Key.NumPadEnter || key == Key.Spacebar || key == Key.Tab) return TourKeyAction.PassToFocus
  if (keyDown && !modified) return when (key) {
    Key.DirectionRight -> TourKeyAction.Next
    Key.DirectionLeft -> TourKeyAction.Back
    Key.Escape -> TourKeyAction.Escape
    else -> TourKeyAction.Swallow
  }
  return TourKeyAction.Swallow
}

internal fun handleTourKey(event: KeyEvent, tour: OnboardingTourState): Boolean = applyTourAction(
  tourKeyAction(
    event.key,
    event.type == KeyEventType.KeyDown,
    event.isMetaPressed || event.isCtrlPressed || event.isAltPressed,
  ),
  tour,
)

internal fun applyTourAction(action: TourKeyAction, tour: OnboardingTourState): Boolean {
  when (action) {
    TourKeyAction.Next -> tour.next()
    TourKeyAction.Back -> tour.back()
    TourKeyAction.Escape -> if (tour.step == OnboardingTourState.LAST_STEP) tour.close() else tour.skip()
    TourKeyAction.PassToFocus -> return false
    TourKeyAction.Swallow -> Unit
  }
  return true
}
