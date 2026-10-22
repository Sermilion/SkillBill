package skillbill.engine.featuretask.phase.prompt.compose

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimePhasePromptComposerProjectInstructionsTest {
  private val instructions =
    PhaseProjectInstructionsResolution.Injected(
      relativePath = ".skill-bill/phases/implement.md",
      content = "Always run the lint wrapper before finishing.\n\n",
      sha256 = "0".repeat(SHA256_HEX_LENGTH),
    )

  @Test
  fun `project instructions sit between the briefing text and the output contract`() {
    val briefing = promptComposerBriefingFor("implement")

    val prompt =
      composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, briefing) { copy(projectInstructions = instructions) }

    val briefingAt = prompt.indexOf(briefing.briefingText)
    val sectionAt = prompt.indexOf("## Project instructions for this phase")
    val contractAt = prompt.indexOf("## Required final output")
    assertContains(prompt, "Always run the lint wrapper before finishing.")
    assertTrue(briefingAt in 0 until sectionAt, "section must follow the briefing text")
    assertTrue(sectionAt < contractAt, "section must precede the output contract")
  }

  @Test
  fun `project instructions survive a terminal-failure retry composition`() {
    val prompt =
      composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor("implement")) {
        copy(projectInstructions = instructions, priorTerminalFailure = "previous attempt failed")
      }

    val sectionAt = prompt.indexOf("## Project instructions for this phase")
    assertTrue(sectionAt >= 0)
    assertTrue(sectionAt < prompt.indexOf("previous attempt failed"), "retry text stays trailing")
  }

  @Test
  fun `no resolved instructions leaves the prompt without a project section`() {
    val briefing = promptComposerBriefingFor("implement")

    val without = composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, briefing)
    val explicitNull = composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, briefing) { copy(projectInstructions = null) }

    assertFalse(without.contains("Project instructions for this phase"))
    assertEquals(without, explicitNull)
  }

  private companion object {
    const val SHA256_HEX_LENGTH = 64
  }
}
