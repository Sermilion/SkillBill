package dev.skillbill.tray.core.ui

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EffectsTest {
  @Test
  fun `navigation effects keep only the newest effect while nothing collects`() = runTest {
    val effects = NavigationEffects<Int>()
    (1..5).forEach(effects::send)

    assertEquals(listOf(5), effects.flow.take(1).toList())
  }

  @Test
  fun `transient effects keep the newest few and drop the oldest first`() = runTest {
    val effects = TransientEffects<Int>()
    (1..10).forEach(effects::send)

    assertEquals(listOf(7, 8, 9, 10), effects.flow.take(TransientEffects.TRANSIENT_EFFECT_CAPACITY).toList())
  }

  @Test
  fun `an effect is delivered to one collector and not replayed to the next`() = runTest {
    val effects = TransientEffects<String>()
    effects.send("saved")

    assertEquals(listOf("saved"), effects.flow.take(1).toList())
    effects.send("again")
    assertEquals(listOf("again"), effects.flow.take(1).toList())
  }

  @Test
  fun `sending never suspends or fails when the buffer is full`() {
    val effects = NavigationEffects<Int>()
    repeat(10_000) { effects.send(it) }
  }
}
