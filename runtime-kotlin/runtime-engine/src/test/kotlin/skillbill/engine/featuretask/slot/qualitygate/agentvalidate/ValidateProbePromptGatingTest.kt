package skillbill.engine.featuretask.slot.qualitygate.agentvalidate

import skillbill.engine.featuretask.model.audit.ValidateProbeCriterion
import skillbill.engine.featuretask.model.audit.ValidateProbeScope
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PROMPT_COMPOSER_ISSUE_KEY
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.phase.prompt.compose.promptComposerBriefingFor
import skillbill.engine.featuretask.slot.ModelDirectiveSupplements
import skillbill.engine.featuretask.slot.qualitygate.VALIDATE_PROBE_VALUE_CONTENT
import skillbill.engine.featuretask.slot.qualitygate.VALIDATE_VALUE_CONTENT
import skillbill.review.spec.model.InteractionScenario
import skillbill.workflow.taskruntime.model.skeleton.PhaseModelProfile
import skillbill.workflow.taskruntime.model.validation.ValidationTestProbeParser
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidateProbePromptGatingTest {
  private val strategy = AgentValidateStrategy()
  private val scope =
    ValidateProbeScope(
      repairedScenarios =
        listOf(
          InteractionScenario(
            scenarioRef = "IS-001",
            criterionRefs = listOf("AC-001"),
            subtaskRefs = listOf(1),
            entryPoint = "POST /carts",
            preconditions = "an empty cart",
            steps = "add an item",
            outcome = "the cart holds one item",
          ),
        ),
      requiredCriteria = listOf(ValidateProbeCriterion("AC-001", "Adding an item grows the cart.")),
    )

  @Test
  fun `a goal-child validate prompt carries the instructions and the probe scope`() {
    val sections = strategy.promptSections(PHASE_VALIDATE, inputs(scope))

    assertTrue(PROBE_MARKER in sections.taskDirective)
    assertTrue("IS-001 (entry point POST /carts; outcome the cart holds one item)" in sections.taskDirective)
    assertTrue("AC-001: Adding an item grows the cart." in sections.taskDirective)
    assertTrue(ValidationTestProbeParser.LINE_GRAMMAR in sections.taskDirective)
    assertEquals(VALIDATE_PROBE_VALUE_CONTENT, sections.valueContent)
  }

  @Test
  fun `empty scope lists render as none`() {
    val sections = strategy.promptSections(PHASE_VALIDATE, inputs(ValidateProbeScope(emptyList(), emptyList())))

    assertTrue("Broken-then-repaired scenarios: none" in sections.taskDirective)
    assertTrue("Required criteria: none" in sections.taskDirective)
  }

  @Test
  fun `standalone and post-QA validate prompts carry no probe scope or instructions`() {
    val sections = strategy.promptSections(PHASE_VALIDATE, inputs(null))

    assertFalse(PROBE_MARKER in sections.taskDirective)
    assertEquals(VALIDATE_VALUE_CONTENT, sections.valueContent)
  }

  @Test
  fun `the step directive stays probe-free`() {
    assertFalse(PROBE_MARKER in strategy.directiveFor(PHASE_VALIDATE))
  }

  @Test
  fun `the opus supplement carries the probe section only when probes are active`() {
    val supplements = ModelDirectiveSupplements(enabled = true)
    val base = PhaseStepPromptSections(taskDirective = "validate")

    val inactive = supplements.supplement(base, strategy, PHASE_VALIDATE, PhaseModelProfile.OPUS_5_5)
    val active = supplements.supplement(base, strategy, PHASE_VALIDATE, PhaseModelProfile.OPUS_5_5) { true }

    assertFalse(OPUS_PROBE_HEADING in inactive.taskDirective)
    assertTrue(OPUS_PROBE_HEADING in active.taskDirective)
  }

  @Test
  fun `a scope that cannot be built fails only the prompt that reads it`() {
    val inputs =
      FeatureTaskRuntimePhasePromptComposeInputs(
        issueKey = PROMPT_COMPOSER_ISSUE_KEY,
        briefing = promptComposerBriefingFor(PHASE_VALIDATE),
        validateProbeScopeSource = { error("repaired scenario missing from the catalog") },
      )

    val base = PhaseStepPromptSections(taskDirective = "x")
    val untouched =
      ModelDirectiveSupplements(enabled = true)
        .supplement(base, strategy, PHASE_VALIDATE, PhaseModelProfile.CANONICAL) { inputs.validateProbesActive }

    assertEquals("x", untouched.taskDirective)
    assertFailsWith<IllegalStateException> { strategy.promptSections(PHASE_VALIDATE, inputs) }
  }

  private fun inputs(probeScope: ValidateProbeScope?) =
    FeatureTaskRuntimePhasePromptComposeInputs(
      issueKey = PROMPT_COMPOSER_ISSUE_KEY,
      briefing = promptComposerBriefingFor(PHASE_VALIDATE),
      validateProbeScopeSource = { probeScope },
    )

  private companion object {
    const val PROBE_MARKER = "Test probes:"
    const val OPUS_PROBE_HEADING = "## Test probes"
  }
}
