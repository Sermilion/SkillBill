package dev.skillbill.desktop.feature.chat

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Keeps a double tap or a held Enter key from issuing Send now twice: each refused press restarts [window], and Enter also needs a key-up. */
internal class SendNowGate(private val window: Duration = 500.milliseconds) {
  private var lastPress: Instant? = null
  private var enterDown = false

  fun pressed(now: Instant, viaEnter: Boolean) {
    lastPress = now
    if (viaEnter) enterDown = true
  }

  fun admit(now: Instant, viaEnter: Boolean): Boolean {
    val tooSoon = lastPress?.let { now - it < window } == true
    val held = viaEnter && enterDown
    pressed(now, viaEnter)
    return !tooSoon && !held
  }

  fun enterReleased() {
    enterDown = false
  }
}
