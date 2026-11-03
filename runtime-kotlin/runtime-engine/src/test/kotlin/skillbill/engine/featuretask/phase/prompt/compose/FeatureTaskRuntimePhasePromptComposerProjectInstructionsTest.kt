package skillbill.engine.featuretask.phase.prompt.compose

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimePhasePromptComposerProjectInstructionsTest {
  private val instructions =
    PhaseProjectInstructionsResolution.Injected(
      path = ".skill-bill/phases/implement.md",
      content = "Always run the lint wrapper before finishing.\n\n",
      sha256 = "0".repeat(SHA256_HEX_LENGTH),
    )

  private val projectStoreInstructions =
    PhaseProjectInstructionsResolution.Injected(
      path = "/home/operator/.skill-bill/projects/p-1/phases/implement.md",
      content = "Use the local emulator profile.\n",
      sha256 = "1".repeat(SHA256_HEX_LENGTH),
    )

  @Test
  fun `project instructions sit between the briefing text and the output contract`() {
    val briefing = promptComposerBriefingFor("implement")

    val prompt =
      composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, briefing) { copy(projectInstructions = listOf(instructions)) }

    val briefingAt = prompt.indexOf(briefing.briefingText)
    val sectionAt = prompt.indexOf("## Project instructions for this phase")
    val contractAt = prompt.indexOf("## Required final output")
    assertContains(prompt, "Always run the lint wrapper before finishing.")
    assertTrue(briefingAt in 0 until sectionAt, "section must follow the briefing text")
    assertTrue(sectionAt < contractAt, "section must precede the output contract")
  }

  @Test
  fun `both instruction files appear under their own paths with the repository file first`() {
    val prompt =
      composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor("implement")) {
        copy(projectInstructions = listOf(instructions, projectStoreInstructions))
      }

    val repoAt = prompt.indexOf(instructions.path)
    val repoContentAt = prompt.indexOf("Always run the lint wrapper before finishing.")
    val storeAt = prompt.indexOf(projectStoreInstructions.path)
    val storeContentAt = prompt.indexOf("Use the local emulator profile.")
    assertEquals(1, Regex("## Project instructions for this phase").findAll(prompt).count())
    assertTrue(repoAt in 0 until repoContentAt, "repository path labels its content")
    assertTrue(repoContentAt < storeAt, "project store file follows the repository file")
    assertTrue(storeAt < storeContentAt, "project store path labels its content")
  }

  @Test
  fun `project instructions survive a terminal-failure retry composition`() {
    val prompt =
      composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor("implement")) {
        copy(projectInstructions = listOf(instructions), priorTerminalFailure = "previous attempt failed")
      }

    val sectionAt = prompt.indexOf("## Project instructions for this phase")
    assertTrue(sectionAt >= 0)
    assertTrue(sectionAt < prompt.indexOf("previous attempt failed"), "retry text stays trailing")
  }

  @Test
  fun `no resolved instructions leaves the prompt without a project section`() {
    val briefing = promptComposerBriefingFor("implement")

    val without = composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, briefing)
    val explicitEmpty =
      composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, briefing) { copy(projectInstructions = emptyList()) }

    assertFalse(without.contains("Project instructions for this phase"))
    assertEquals(without, explicitEmpty)
  }

  private companion object {
    const val SHA256_HEX_LENGTH = 64
  }
}
