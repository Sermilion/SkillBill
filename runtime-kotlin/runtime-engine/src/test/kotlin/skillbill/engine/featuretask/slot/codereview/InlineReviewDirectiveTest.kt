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
import skillbill.engine.featuretask.slot.runner.DefaultPhaseRunner
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
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
      Files.readString(SlotBaselineTestResources.resolve("${SlotBaselinePaths.STANDALONE}/prompts/review.txt"))

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
  fun `delegated review prompt carries the review directive`() {
    val home = Files.createTempDirectory("delegated-review-directive")
    try {
      val database =
        sqliteSessionFactoryForTests(
          userHome = home,
          dbPathOverride = home.resolve("metrics.db").toString(),
          environment = emptyMap(),
        )
      val strategy =
        DelegatedReviewStrategy(
          DefaultPhaseRunner(
            GoalRunnerSubtaskLauncher { error("The directive lookup must not launch.") },
            NoopWorkflowGitOperations,
          ),
          scriptedDelegatedReviewRunner(database, home, LaneScript()),
        )

      val directive = strategy.directiveFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)

      assertTrue(directive.contains("\n## Review mode argument\n"), directive)
      assertFalse(directive.contains("## Depth\n"), "the inline worker body stays out of the delegated review")
      assertFalse(directive.contains("Project authoring discipline"), "delegated review gets no authoring authority")
    } finally {
      home.toFile().deleteRecursively()
    }
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

  private fun compose(
    target: ReviewTarget,
    agentAddonsSection: String = "",
  ): String =
    InlineReviewDirective.compose(
      target = target,
      baseRevision = NORMALIZED_SHA,
      headRevision = NORMALIZED_SHA,
      specPath = Path.of(SlotBaselineFullRunCapture.SPEC_REFERENCE),
      agentAddonsSection = agentAddonsSection,
    )
}
