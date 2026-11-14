package skillbill.engine.featuretask.slot.qualitygate.agentvalidate

import skillbill.contracts.config.projectchecks.ProjectCheckCategoryConfig
import skillbill.contracts.config.projectchecks.ProjectCheckCommand
import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot
import skillbill.engine.featuretask.model.phase.ValidationCommandLaunchTransport
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
          packValidationCommand = "./gradlew check",
        ),
      )
    val instructions =
      listOf(sections.taskDirective, sections.valueContent, strategy.directiveFor(PHASE_VALIDATE))
        .joinToString("\n")
    assertTrue("admitted saved validation" in instructions)
    assertFalse("Discover the validation checks required by this project" in instructions)
    assertEquals(VALIDATE_VALUE_CONTENT, sections.valueContent)
    listOf("Test probes:", "test_probes", "guarded_ref", "re-probe", "removes that behavior").forEach { removed ->
      assertFalse(removed in instructions, removed)
    }
  }

  @Test
  fun `known-empty transport tells the agent not to invent commands`() {
    val snapshot = emptyValidationSnapshot()
    val sections =
      AgentValidateStrategy().promptSections(
        PHASE_VALIDATE,
        FeatureTaskRuntimePhasePromptComposeInputs(
          issueKey = PROMPT_COMPOSER_ISSUE_KEY,
          briefing = promptComposerBriefingFor(PHASE_VALIDATE),
          validationLaunchTransport = ValidationCommandLaunchTransport.KnownEmpty(snapshot),
        ),
      )
    assertTrue("explicitly empty" in sections.taskDirective)
    assertFalse("Discover the validation checks" in sections.taskDirective)
    assertTrue("invent commands" in sections.taskDirective)
  }

  @Test
  fun `commands transport names the saved argv and forbids rediscovery`() {
    val snapshot =
      emptyValidationSnapshot(
        ProjectCheckCommand(listOf("./gradlew", "check"), "."),
      )
    val rendered = "command 1\nworking directory: '.'\nargv: './gradlew' 'check'"
    val sections =
      AgentValidateStrategy().promptSections(
        PHASE_VALIDATE,
        FeatureTaskRuntimePhasePromptComposeInputs(
          issueKey = PROMPT_COMPOSER_ISSUE_KEY,
          briefing = promptComposerBriefingFor(PHASE_VALIDATE),
          validationLaunchTransport = ValidationCommandLaunchTransport.Commands(snapshot, rendered),
        ),
      )
    assertTrue(rendered in sections.taskDirective)
    assertTrue("listed order" in sections.taskDirective)
    assertTrue("working directory" in sections.taskDirective)
    assertFalse("Discover the validation checks" in sections.taskDirective)
    assertFalse(sections.runsBuildGate)
  }

  private fun emptyValidationSnapshot(vararg commands: ProjectCheckCommand): ProjectCheckCommandSnapshot =
    ProjectCheckCommandSnapshot(
      projectId = "validate-prompt-fixture",
      buildCommands = null,
      validationCommands = ProjectCheckCategoryConfig(commands.toList()),
      parserMetadata = null,
      gradleWrapper = null,
    ).frozen()
}
