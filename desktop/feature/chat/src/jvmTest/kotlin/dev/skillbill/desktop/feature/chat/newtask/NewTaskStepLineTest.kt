package dev.skillbill.desktop.feature.chat.newtask

import kotlin.test.Test
import kotlin.test.assertEquals

class NewTaskStepLineTest {
  @Test
  fun `reduced motion holds the plan dot amber without glow at any progress`() {
    for (progress in listOf(null, 0f, .3f, .9f)) {
      NEW_TASK_STEPS.indices.forEach { step ->
        val expected = if (step == 0) DotFrame(1f, 0f) else DotFrame(0f, 0f)
        assertEquals(expected, stepDotFrame(step, progress, reducedMotion = true))
      }
    }
  }

  @Test
  fun `the amber dot moves one step every seventh of the cycle`() {
    NEW_TASK_STEPS.indices.forEach { active ->
      val progress = active / 7f + PAST_PREVIOUS_FADE
      NEW_TASK_STEPS.indices.forEach { step ->
        val expected = if (step == active) DotFrame(1f, 1f) else DotFrame(0f, 0f)
        assertEquals(expected, idleDotFrame(step, progress), "step $step while step $active is active")
      }
    }
  }

  private companion object {
    /** The previous dot is still fading at exactly 1/7, because 1/7 falls between the 10% hold and the 16% fade end. */
    const val PAST_PREVIOUS_FADE = .02f
  }
}
