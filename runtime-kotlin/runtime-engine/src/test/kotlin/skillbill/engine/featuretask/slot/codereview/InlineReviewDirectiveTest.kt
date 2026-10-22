package skillbill.engine.featuretask.slot.codereview

import skillbill.agentaddon.model.AgentAddonPromptFormatter
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelectionEntry
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.runner.SlotBaselineFullRunCapture
import skillbill.engine.featuretask.runner.SlotBaselinePaths
import skillbill.engine.featuretask.runner.SlotBaselineTestResources
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val NORMALIZED_SHA = "__NORMALIZED_SHA40__"

class InlineReviewDirectiveTest {
  @Test
  fun `last-commit prompt is byte-identical to the pre-change fixture`() {
    val fixture =
      Files.readString(SlotBaselineTestResources.resolve("${SlotBaselinePaths.GOAL_CHILD_VALIDATE}/prompts/review.txt"))

    assertEquals(fixture, compose(ReviewTarget.LastCommit))
  }

  @Test
  fun `uncommitted target reviews the workspace against HEAD`() {
    val prompt = compose(ReviewTarget.Uncommitted)

    assertTrue(prompt.startsWith("Review the uncommitted changes in this repository workspace against `HEAD`"))
    assertTrue(prompt.contains("`git diff HEAD`"))
    assertTrue(NORMALIZED_SHA !in prompt)
    assertFalse(prompt.contains("last commit", ignoreCase = true))
  }

  @Test
  fun `named commit target reviews that commit against its first parent`() {
    val prompt = compose(ReviewTarget.Commit("c0ffee"))

    assertTrue(prompt.startsWith("Review commit `c0ffee` against its first parent `c0ffee^`.\n"))
    assertTrue(prompt.contains("`git diff c0ffee^ c0ffee`"))
    assertTrue(NORMALIZED_SHA !in prompt)
    assertFalse(prompt.contains("last commit", ignoreCase = true))
  }

  @Test
  fun `a scoped supplied-diff target reads the diff file and drops the no-blob rule`() {
    val target =
      ReviewTarget.Scoped(ParallelReviewScope.BRANCH, "base1", "head1", Path.of("/tmp/review.diff"))

    val prompt = compose(target)

    assertTrue(prompt.startsWith("Review exactly the diff in `/tmp/review.diff`, taken between `base1` and `head1`."))
    assertFalse(prompt.contains("pre-baked diff blob"))
  }

  @Test
  fun `inline review prompt carries the review and inline review directives`() {
    val prompt = compose(ReviewTarget.LastCommit)

    assertTrue(prompt.contains("\n## Review mode argument\n"))
    assertTrue(prompt.contains("## Depth\n"))
    assertFalse(prompt.contains("read_evidence"), "the retired broker worker's evidence contract stays out")
  }

  @Test
  fun `selected add-ons are appended after the review instructions`() {
    val selection =
      HydratedAgentAddonSelection(
        listOf(
          HydratedAgentAddonSelectionEntry(
            PersistedAgentAddonSelectionEntry("first", "local:first", "a".repeat(64)),
            "first",
            "first body\n",
          ),
        ),
      )
    val section = AgentAddonPromptFormatter.format(selection)

    val prompt = compose(ReviewTarget.LastCommit, section)

    assertEquals(compose(ReviewTarget.LastCommit).trimEnd() + "\n\n" + section, prompt)
  }

  @Test
  fun `a code-quality rubric is added before the evidence record without altering the rest of the prompt`() {
    val rubric = "Prefer cohesive helpers.\nIgnore: security defects belong to the security specialist."
    val without = compose(ReviewTarget.LastCommit)
    val with = compose(ReviewTarget.LastCommit, qualityRubric = rubric)

    val section = CodeQualityLaneSection.render(rubric)
    val evidenceStart = without.indexOf("Emit the bounded authoring evidence record")
    assertEquals(without.substring(0, evidenceStart) + section + "\n" + without.substring(evidenceStart), with)
    assertTrue(rubric in section)
    assertTrue("adds a code-quality lane" in section)
    assertTrue("responsible for every review area" in section)
    assertTrue("apply only to delegated review" in section)
  }

  private fun compose(
    target: ReviewTarget,
    agentAddonsSection: String = "",
    qualityRubric: String? = null,
  ): String =
    InlineReviewDirective.compose(
      target = target,
      revisions = NORMALIZED_SHA to NORMALIZED_SHA,
      specPath = Path.of(SlotBaselineFullRunCapture.SPEC_REFERENCE),
      agentAddonsSection = agentAddonsSection,
      qualityRubric = qualityRubric,
    )
}
