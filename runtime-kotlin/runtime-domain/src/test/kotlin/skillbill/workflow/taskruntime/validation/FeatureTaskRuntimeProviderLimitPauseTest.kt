package skillbill.workflow.taskruntime.validation

import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeProviderLimitSignal
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimeProviderLimitPauseTest {
  @Test
  fun `classifier accepts only shared builder pause copy`() {
    val builderReason =
      FeatureTaskRuntimeProviderLimitPause.reason(
        phaseLabel = "Feature-task-runtime phase 'validate'",
        agentId = "claude",
        resumePhaseId = "validate",
        signal = FeatureTaskRuntimeProviderLimitSignal(evidence = "You've hit your usage limit", resetHint = null),
      )

    assertTrue(FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(builderReason))
    assertFalse(builderReason.contains("Access resets"))
    assertFalse(
      FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(
        "Goal assessment did not accept the goal: assessment_unavailable.",
      ),
    )
    assertFalse(
      FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(
        "Feature-task-runtime phase 'implement' agent exited with non-zero status 1.\n" +
          "e: RateLimiter.kt:429:11 unresolved reference: limit",
      ),
    )
  }

  @Test
  fun `builder appends Access resets only when the signal has a reset hint`() {
    val withReset =
      FeatureTaskRuntimeProviderLimitPause.reason(
        phaseLabel = "Feature-task-runtime phase 'validate'",
        agentId = "codex",
        resumePhaseId = "validate",
        signal =
          FeatureTaskRuntimeProviderLimitSignal(
            evidence = "usage_limit_exceeded",
            resetHint = "Nov 5th, 2026 2:05 AM",
          ),
      )

    assertTrue(withReset.contains("Access resets Nov 5th, 2026 2:05 AM."))
    assertTrue(FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(withReset))
  }
}
