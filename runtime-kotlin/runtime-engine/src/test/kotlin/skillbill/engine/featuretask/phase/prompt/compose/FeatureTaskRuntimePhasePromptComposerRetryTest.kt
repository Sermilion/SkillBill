
package skillbill.engine.featuretask.phase.prompt.compose

import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class FeatureTaskRuntimePhasePromptComposerRetryTest {
  @Test
  fun `an operator blocked-phase retry decision is delivered only to its matching phase`() {
    val reason = "Use fresh-process isolation for Codex CLI workers."
    val retry =
      FeatureTaskRuntimeOperatorBlockRetry(
        phaseId = "implement",
        reason = reason,
        retriedAt = "2026-07-21T16:30:00Z",
      )

    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("implement"),
      ) { copy(operatorBlockRetry = retry) }

    assertContains(prompt, "Operator-applied blocked-phase retry decision")
    assertContains(prompt, reason)
    assertFailsWith<IllegalArgumentException> {
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("audit"),
      ) { copy(operatorBlockRetry = retry) }
    }
  }
}
