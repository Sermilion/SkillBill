package skillbill.engine.featuretask.phase.prompt.compose

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeImplementationContinuation
import skillbill.goalrunner.subtaskreview.FeatureTaskRuntimeVerificationSignalKeys
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimePhasePromptComposerRepairTest {
  @Test
  fun `audit prompt carries satisfied criteria forward without rechecking them`() {
    val auditPrompt = composePromptForPhase("audit")
    assertContains(auditPrompt, "inspect only the unresolved criteria in the last accepted audit report")
    assertTrue(!auditPrompt.contains("prior_gap_memory"))
    assertTrue(!auditPrompt.contains("Prior-gap memory"))
  }

  @Test
  fun `planning and audit preserve every existing criterion identifier and its wording`() {
    val criteria = listOf("AC-007. Preserve this requirement.", "AC-023. Preserve this one too.")
    val options = PromptComposerBriefingOptions(acceptanceCriteria = criteria)
    listOf("plan", "audit").forEach { phase ->
      val briefing = promptComposerBriefingFor(phase, options)
      assertTrue(briefing.acceptanceCriteria == criteria)
      val section =
        briefing.briefingText.substringAfter("acceptance_criteria:\n")
          .substringBefore("mandates_and_overrides:")
      assertTrue(section.lines().filter(String::isNotBlank).map(String::trimStart) == criteria)
      assertFalse(briefing.briefingText.contains("durably_closed_criteria"))
    }
  }

  @Test
  fun `verifying-phase prompts leave runtime-minted keys out of the agent contract`() {
    val keys = FeatureTaskRuntimeVerificationSignalKeys
    val reviewPrompt = composePromptForPhase("review")
    val auditPrompt = composePromptForPhase("audit")

    assertFalse(reviewPrompt.contains(keys.REVIEW_RUN_ID), "the review run id is runtime-minted")
    assertContains(reviewPrompt, "approved or requested changes", false, "review states its verdict in prose")
    assertFalse(auditPrompt.contains("non_blocking_findings"))
    assertContains(
      auditPrompt,
      "remaining acceptance criteria in prose",
      false,
      "audit states its open criteria in prose",
    )
  }

  @Test
  fun `preplan plan and implement ask for plain prose without a JSON value example`() {
    promptComposerProjectionExampleCases().forEach { (phaseId, briefing) ->
      val prompt = composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, briefing)
      assertFalse(prompt.contains("```json"), "the $phaseId prompt must not teach a JSON value example")
      assertFalse(prompt.contains("projection_kind"), "the $phaseId prompt must not name a projection kind")
      assertContains(prompt.substringAfter("## Required final output"), "plain prose")
    }
  }

  @Test
  fun `an incomplete-work retry carries the continuation directive and not the schema-correction directive`() {
    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("implement"),
      ) {
        copy(
          implementationContinuation =
            FeatureTaskRuntimeImplementationContinuation(
              phaseId = "implement",
              segmentNumber = 2,
              priorValueSegments = listOf("segment one prose"),
              latestPrompt = "optional directive",
              failureDisposition = null,
            ),
        )
      }

    assertContains(prompt, "segment 2")
    assertContains(prompt, "Prior segment summaries")
    assertContains(prompt, "segment one prose")
    assertContains(prompt, "optional directive")
    assertTrue(
      !prompt.contains("openObligationIds") && !prompt.contains("Still open"),
      "continuation prompts carry stuffed value history, not openObligationIds",
    )
    assertTrue(
      !prompt.contains("REJECTED by the schema gate"),
      "an honest partial receipt is not a schema failure",
    )
  }

  @Test
  fun `resumed audit repair receives its saved reports without an implementation receipt contract`() {
    val prompt =
      composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor("audit_implement_fix")) {
        copy(
          implementationContinuation =
            FeatureTaskRuntimeImplementationContinuation(
              phaseId = "audit_implement_fix",
              segmentNumber = 2,
              priorValueSegments = listOf("Guard scanner repaired. Capability leaks remain."),
              latestPrompt = "Close the remaining authority paths.",
              failureDisposition = "needs_user_action",
            ),
        )
      }

    assertContains(prompt, "Resume the saved audit repair")
    assertContains(prompt, "Guard scanner repaired. Capability leaks remain.")
    assertContains(prompt, "Close the remaining authority paths.")
    assertContains(prompt, "needs_user_action")
    assertFalse(prompt.contains("implementation_receipt"))
  }
}
