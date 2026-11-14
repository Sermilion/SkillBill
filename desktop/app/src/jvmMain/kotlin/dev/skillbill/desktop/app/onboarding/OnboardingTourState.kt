package dev.skillbill.desktop.app.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(WindowScope::class)
class OnboardingTourState(
  private val settings: AppSettingsRepository,
  private val scope: WindowCoroutineScope,
) {
  var open by mutableStateOf(false)
    private set
  var step by mutableIntStateOf(WELCOME_STEP)
    private set
  var beat by mutableIntStateOf(0)
    private set
  var replayToken by mutableIntStateOf(0)
    private set
  private var launchStarted = false

  fun startOnLaunch(): Job? {
    if (launchStarted) return null
    launchStarted = true
    return scope.scope.launch {
      if (settings.readOnboardingTourSeen() == Outcome.Success(false)) {
        settings.markOnboardingTourSeen()
        if (!open) show()
      }
    }
  }

  fun show() {
    step = WELCOME_STEP
    beat = 0
    open = true
    replayToken++
  }

  fun next() {
    if (beat < tourBeatCount(step) - 1) beat++ else goTo(step + 1)
  }

  fun back() {
    if (beat > 0) beat-- else goTo(step - 1)
  }

  fun goTo(step: Int) {
    this.step = step.coerceIn(WELCOME_STEP, LAST_STEP)
    beat = 0
    replayToken++
  }

  fun replayStep() {
    beat = 0
    replayToken++
  }

  fun skip() = goTo(LAST_STEP)

  fun close() { open = false }

  companion object {
    const val WELCOME_STEP = 0
    const val LAST_STEP = 9
  }
}
