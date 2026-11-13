package skillbill.engine.featuretask.slot.qualitygate.agentvalidate

import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PROMPT_COMPOSER_ISSUE_KEY
import skillbill.engine.featuretask.phase.prompt.compose.promptComposerBriefingFor
import skillbill.engine.featuretask.slot.qualitygate.VALIDATE_VALUE_CONTENT
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentValidatePromptTest {
  @Test
  fun `validation requests project checks without mutation experiments or probe reports`() {
    val strategy = AgentValidateStrategy()
    val sections =
      strategy.promptSections(
        PHASE_VALIDATE,
        FeatureTaskRuntimePhasePromptComposeInputs(
          issueKey = PROMPT_COMPOSER_ISSUE_KEY,
          briefing = promptComposerBriefingFor(PHASE_VALIDATE),
        ),
      )
    val instructions =
      listOf(sections.taskDirective, sections.valueContent, strategy.directiveFor(PHASE_VALIDATE))
        .joinToString("\n")
    assertTrue("required project check" in instructions)
    assertEquals(VALIDATE_VALUE_CONTENT, sections.valueContent)
    listOf("Test probes:", "test_probes", "guarded_ref", "re-probe", "removes that behavior").forEach { removed ->
      assertFalse(removed in instructions, removed)
    }
  }
}
