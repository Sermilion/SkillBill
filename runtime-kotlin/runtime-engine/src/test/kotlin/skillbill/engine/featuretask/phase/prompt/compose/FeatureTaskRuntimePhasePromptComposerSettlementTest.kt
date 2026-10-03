package skillbill.engine.featuretask.phase.prompt.compose

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimePhasePromptComposerSettlementTest {
  @Test
  fun `settling steps with a settlement target are told to settle through the MCP tools`() {
    val target = FeatureTaskRuntimePhaseSettlementTarget(workflowId = "wftr-20260904-210526-r3x0", attempt = 2)
    listOf(
      "preplan",
      "plan",
      "implement",
      "simplify",
      "audit",
      "review",
      "verify_findings",
      "implement_fix",
      "validate",
      "write_history",
      "pr",
    ).forEach { phaseId ->
      val prompt =
        composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor(phaseId)) {
          copy(phaseSettlement = target)
        }

      assertContains(prompt, "## Required final output (durable settlement)", false, "settlement heading for $phaseId")
      assertContains(prompt, "mcp__skill-bill__feature_task_phase_complete", false, "complete tool for $phaseId")
      assertContains(prompt, "mcp__skill-bill__feature_task_phase_block", false, "block tool for $phaseId")
      assertContains(prompt, "workflow_id \"wftr-20260904-210526-r3x0\"", false, "pinned workflow id for $phaseId")
      assertContains(prompt, "phase_id \"$phaseId\", attempt 2", false, "pinned attempt for $phaseId")
      assertContains(prompt, FALLBACK_HEADING, false, "minimal fallback for $phaseId")
      assertFalse(prompt.contains("validated schema gate"), "no envelope for $phaseId")
      assertTrue(
        prompt.indexOf("## Required final output (durable settlement)") < prompt.indexOf(FALLBACK_HEADING),
        "settlement precedes the fallback final object for $phaseId",
      )
    }
  }

  @Test
  fun `runtime-owned turns get no settlement directive`() {
    val target = FeatureTaskRuntimePhaseSettlementTarget(workflowId = "wftr-20260904-210526-r3x0", attempt = 1)
    listOf("build", "commit_push").forEach { phaseId ->
      val prompt =
        composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor(phaseId)) {
          copy(phaseSettlement = target)
        }

      assertFalse(prompt.contains("durable settlement"), "no settlement directive for $phaseId")
      assertFalse(prompt.contains("validated schema gate"), "no envelope for $phaseId")
    }
  }

  @Test
  fun `a launch without a settlement target prints only the minimal final object`() {
    val prompt = composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor("implement"))

    assertFalse(prompt.contains("durable settlement"))
    assertFalse(prompt.contains(FALLBACK_HEADING))
    assertFalse(prompt.contains("validated schema gate"))
    assertContains(prompt, "## Required final output\n")
    assertContains(prompt, "plain prose")
  }

  private companion object {
    const val FALLBACK_HEADING = "## Fallback final output (only when the settlement tools are unavailable)"
  }
}
