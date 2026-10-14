package skillbill.engine.goalrunner.intake

import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.error.core.InvalidFeatureSpecPreparationRequestError
import skillbill.review.spec.GovernedSpecSectionParser
import skillbill.review.spec.GovernedSpecSectionParser.ACCEPTANCE_CRITERIA_PREFIX
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanSpecOrigin
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalIntakePreparationTest {
  private val preparation = testGoalIntakePreparation()

  @Test
  fun `prepare finds no bundle for an intake and writes no manifest or subtask spec`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-intake-no-bundle")
    val parentSpec = writeParentSpec(repoRoot, TABLE_SPEC)

    val prepared = preparation.prepare(request(repoRoot))

    assertNull(prepared)
    assertEquals(TABLE_SPEC, Files.readString(parentSpec))
    assertEquals(listOf("spec.md"), directoryEntries(parentSpec))
  }

  @Test
  fun `plan intake keeps a listed operator spec byte for byte and writes nothing else`() {
    val repoRoot = Files.createTempDirectory("skillbill-plan-intake-listed")
    val listedSpec = "# SKILL-77 Listed\n\n## Acceptance Criteria\n\n1. The desktop app compiles.\n"
    val parentSpec = writeParentSpec(repoRoot, listedSpec)

    val seed = intakePlanSpec("SKILL-77", PARENT_SPEC_PATH, repoRoot)

    assertEquals(FeatureTaskRuntimePlanSpecOrigin.OPERATOR, seed.specOrigin)
    assertEquals(parentSpec, seed.specPath)
    assertEquals(listedSpec, Files.readString(parentSpec))
    assertEquals(listOf("spec.md"), directoryEntries(parentSpec))
  }

  @Test
  fun `plan intake replaces a table-form spec in place with quoted prose and a readable list`() {
    val repoRoot = Files.createTempDirectory("skillbill-plan-intake-table")
    val parentSpec = writeParentSpec(repoRoot, TABLE_SPEC)

    val seed = intakePlanSpec("SKILL-77", PARENT_SPEC_PATH, repoRoot)

    val rewritten = Files.readString(parentSpec)
    assertEquals(FeatureTaskRuntimePlanSpecOrigin.SEEDED, seed.specOrigin)
    assertEquals(parentSpec, seed.specPath)
    assertContains(rewritten, "> | AC-01 | The desktop app compiles. |")
    assertTrue(acceptanceCriteria(rewritten).isNotEmpty())
    assertEquals(listOf("spec.md"), directoryEntries(parentSpec))
  }

  @Test
  fun `plan intake seeds free text at the slugged feature spec path`() {
    val repoRoot = Files.createTempDirectory("skillbill-plan-intake-free-text")

    val seed = intakePlanSpec("SKILL-78", "SKILL-78 Add dark mode\nUsers can switch themes.", repoRoot)

    val expected = repoRoot.resolve(".feature-specs/SKILL-78-add-dark-mode/spec.md")
    assertEquals(FeatureTaskRuntimePlanSpecOrigin.SEEDED, seed.specOrigin)
    assertEquals(expected, seed.specPath)
    assertTrue(acceptanceCriteria(Files.readString(expected)).isNotEmpty())
    assertEquals(listOf("spec.md"), directoryEntries(expected))
  }

  @Test
  fun `a planned spec path is computed without writing and refuses intake without requirements`() {
    val repoRoot = Files.createTempDirectory("skillbill-plan-intake-planned")

    val planned =
      preparation.resolvePlanSpec("SKILL-78", "SKILL-78 Add dark mode\nUsers can switch themes.", repoRoot).specPath

    assertEquals(repoRoot.resolve(".feature-specs/SKILL-78-add-dark-mode/spec.md"), planned)
    assertTrue(Files.notExists(repoRoot.resolve(".feature-specs")))
    assertFailsWith<InvalidFeatureSpecPreparationRequestError> {
      preparation.resolvePlanSpec("SKILL-78", "SKILL-78", repoRoot)
    }
  }

  private fun intakePlanSpec(
    issueKey: String,
    intake: String,
    repoRoot: Path,
  ) = preparation.intakePlanSpec(issueKey, intake, repoRoot, preparation.resolvePlanSpec(issueKey, intake, repoRoot))

  private fun directoryEntries(spec: Path): List<String> =
    Files.list(spec.parent).use { paths -> paths.map { it.fileName.toString() }.sorted().toList() }

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
