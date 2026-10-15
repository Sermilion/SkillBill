package dev.skillbill.tray.core.ui

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Delivery of one-shot effects to a screen, bounded so a screen that is not collecting can never grow memory.
 *
 * Delivery is deliberately not exactly-once: an effect sent while nothing collects waits in the buffer, a full buffer drops
 * its oldest entry, and an effect taken by a collector that is then cancelled is gone. Anything the user must not miss
 * belongs in UI state (see [Feedback]), never in an effect.
 */
open class BoundedEffects<E : Any>(capacity: Int) {
  private val channel = Channel<E>(capacity, BufferOverflow.DROP_OLDEST)

  /** One collector at a time receives each effect; a second collector competes for the same entries. */
  val flow: Flow<E> = channel.receiveAsFlow()

  /** Never suspends and never fails; a full buffer drops its oldest effect to make room. */
  fun send(effect: E) {
    channel.trySend(effect)
  }
}

/** Navigation requests: only the latest intent matters, so one slot, newest wins. */
class NavigationEffects<E : Any> : BoundedEffects<E>(NAVIGATION_EFFECT_CAPACITY) {
  companion object {
    const val NAVIGATION_EFFECT_CAPACITY = 1
  }
}

/** Transient feedback (a brief confirmation): a few slots, oldest dropped first. */
class TransientEffects<E : Any> : BoundedEffects<E>(TRANSIENT_EFFECT_CAPACITY) {
  companion object {
    const val TRANSIENT_EFFECT_CAPACITY = 4
  }
}
