package skillbill.engine.goalrunner.intake

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationWriter
import skillbill.engine.goalrunner.manifest.TestNoopGoalPlanningManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.featurespec.model.FeatureSpecPathResolveResult
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.review.spec.GovernedSpecSectionParser
import skillbill.review.spec.GovernedSpecSectionParser.ACCEPTANCE_CRITERIA_PREFIX
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoalIntakePreparationTest {
  private val preparation =
    GoalIntakePreparation(
      TestNoopGoalPlanningManifestStore,
      FeatureSpecPreparationWriter(
        testDecompositionManifestValidator,
        TestDecompositionManifestStore,
        testDecompositionManifestWriter,
      ),
      FeatureSpecPathResolverPort { input -> FeatureSpecPathResolveResult.NoMatch(input.issueKey, input.repoRoot) },
      TestDecompositionManifestStore,
      NoopWorkflowGitOperations,
    )

  @Test
  fun `a spec without a readable acceptance list becomes prose intake for a runtime-written spec`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-intake-unlisted")
    val parentSpec = writeParentSpec(repoRoot, TABLE_SPEC)

    preparation.prepare(request(repoRoot))

    val rewritten = Files.readString(parentSpec)
    assertContains(rewritten, "> | AC-01 | The desktop app compiles. |")
    assertTrue(acceptanceCriteria(rewritten).isNotEmpty())
    assertTrue(acceptanceCriteria(Files.readString(subtaskSpec(parentSpec))).isNotEmpty())
  }

  @Test
  fun `a spec with a readable acceptance list is kept verbatim`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-intake-listed")
    val listedSpec = "# SKILL-77 Listed\n\n## Acceptance Criteria\n\n1. The desktop app compiles.\n"
    val parentSpec = writeParentSpec(repoRoot, listedSpec)

    preparation.prepare(request(repoRoot))

    assertEquals(listedSpec, Files.readString(parentSpec))
    assertTrue(acceptanceCriteria(Files.readString(subtaskSpec(parentSpec))).isNotEmpty())
  }

  private fun acceptanceCriteria(specText: String): List<String> =
    GovernedSpecSectionParser.parseListSection(specText) { it.startsWith(ACCEPTANCE_CRITERIA_PREFIX) }

  private fun writeParentSpec(
    repoRoot: Path,
    text: String,
  ): Path {
    val parentSpec = repoRoot.resolve(PARENT_SPEC_PATH)
    Files.createDirectories(parentSpec.parent)
    Files.writeString(parentSpec, text)
    return parentSpec
  }

  private fun subtaskSpec(parentSpec: Path): Path =
    Files.list(parentSpec.parent).use { paths ->
      paths.filter { it.fileName.toString().startsWith("spec_subtask_1") }.findFirst().orElseThrow()
    }

  private fun request(repoRoot: Path) =
    GoalRunnerRunRequest(
      issueKey = "SKILL-77",
      repoRoot = repoRoot,
      invokedAgentId = "claude",
      intake = PARENT_SPEC_PATH,
    )

  private companion object {
    const val PARENT_SPEC_PATH = ".feature-specs/SKILL-77-table-criteria/spec.md"
    val TABLE_SPEC =
      """
      # SKILL-77 Table criteria

      ## Outcome

      The desktop app is modular.

      ## Acceptance criteria

      | ID | Required final state |
      | --- | --- |
      | AC-01 | The desktop app compiles. |
      """.trimIndent()
  }
}
