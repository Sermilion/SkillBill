package dev.skillbill.desktop.feature.chat.newtask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewTaskStepLineTest {
  @Test
  fun `the sweep starts hidden at the plan dot`() {
    assertEquals(SweepFrame(0f, 0f), sweepFrame(0f))
  }

  @Test
  fun `the sweep reaches the pr dot fully visible and holds there while it fades out`() {
    assertEquals(SweepFrame(1f, 1f), sweepFrame(.85f))
    val fading = sweepFrame(.95f)
    assertEquals(1f, fading.reach)
    assertTrue(fading.alpha in 0f..<1f, "alpha ${fading.alpha} should be fading")
    assertEquals(SweepFrame(1f, 0f), sweepFrame(1f))
  }
}
