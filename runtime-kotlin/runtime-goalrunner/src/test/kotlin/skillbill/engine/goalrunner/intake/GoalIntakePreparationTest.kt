package skillbill.engine.goalrunner.intake

import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.error.core.InvalidFeatureSpecPreparationRequestError
import skillbill.ports.projectstore.FixedProjectStorePort
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
  private val store = FixedProjectStorePort(Files.createTempDirectory("skillbill-goal-intake-store"))
  private val specsRoot = store.root.resolve("specs")
  private val preparation = testGoalIntakePreparation(projectStore = store)

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
    val parentSpec = writeStoreSpec(listedSpec)

    val seed = intakePlanSpec("SKILL-77", parentSpec.toString(), repoRoot)

    assertEquals(FeatureTaskRuntimePlanSpecOrigin.OPERATOR, seed.specOrigin)
    assertEquals(parentSpec, seed.specPath)
    assertEquals(listedSpec, Files.readString(parentSpec))
    assertEquals(listOf("spec.md"), directoryEntries(parentSpec))
  }

  @Test
  fun `plan intake replaces a table-form spec in place with quoted prose and a readable list`() {
    val repoRoot = Files.createTempDirectory("skillbill-plan-intake-table")
    val parentSpec = writeStoreSpec(TABLE_SPEC)

    val seed = intakePlanSpec("SKILL-77", parentSpec.toString(), repoRoot)

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

    val expected = specsRoot.resolve("SKILL-78-add-dark-mode/spec.md")
    assertEquals(FeatureTaskRuntimePlanSpecOrigin.SEEDED, seed.specOrigin)
    assertEquals(expected, seed.specPath)
    assertTrue(acceptanceCriteria(Files.readString(expected)).isNotEmpty())
    assertEquals(listOf("spec.md"), directoryEntries(expected))
  }

  @Test
  fun `plan intake names the bundle from the first clause of a long first line`() {
    val repoRoot = Files.createTempDirectory("skillbill-plan-intake-long-line")
    val intake =
      "SKILL-79 Linear progress sync through MCP: let a goal run link to a Linear issue and keep one comment updated."

    val seed = intakePlanSpec("SKILL-79", intake, repoRoot)

    assertEquals(specsRoot.resolve("SKILL-79-linear-progress-sync-through-mcp/spec.md"), seed.specPath)
  }

  @Test
  fun `a planned spec path is computed without writing and refuses intake without requirements`() {
    val repoRoot = Files.createTempDirectory("skillbill-plan-intake-planned")

    val planned =
      preparation.resolvePlanSpec("SKILL-78", "SKILL-78 Add dark mode\nUsers can switch themes.", repoRoot).specPath

    assertEquals(specsRoot.resolve("SKILL-78-add-dark-mode/spec.md"), planned)
    assertTrue(Files.notExists(specsRoot))
    assertTrue(Files.notExists(repoRoot.resolve(".feature-specs")))
    assertFailsWith<InvalidFeatureSpecPreparationRequestError> {
      preparation.resolvePlanSpec("SKILL-78", "SKILL-78", repoRoot)
    }
  }

  @Test
  fun `the bundle name skips path tokens and keeps slashed words`() {
    val repoRoot = Files.createTempDirectory("skillbill-plan-intake-paths")
    val named =
      mapOf(
        "SKILL-80 /home/u/s.png starting a goal from New task chat creates bad title" to
          "SKILL-80-starting-a-goal-from-new-task",
        "SKILL-81 Fix goal/phase title: more detail" to "SKILL-81-fix-goal-phase-title",
      )

    named.forEach { (intake, directory) ->
      val planned = preparation.resolvePlanSpec(intake.substringBefore(' '), intake, repoRoot).specPath
      assertEquals(specsRoot.resolve("$directory/spec.md"), planned)
    }
  }

  @Test
  fun `a path-only first line names the bundle from the next line and a path-only intake stays admitted`() {
    val repoRoot = Files.createTempDirectory("skillbill-plan-intake-path-only")

    val nextLine = preparation.resolvePlanSpec("SKILL-82", "SKILL-82 /home/u/s.png\nAdd dark mode", repoRoot).specPath
    val pathOnly = preparation.resolvePlanSpec("SKILL-83", "SKILL-83 /home/u/s.png", repoRoot).specPath

    assertEquals(specsRoot.resolve("SKILL-82-add-dark-mode/spec.md"), nextLine)
    assertEquals(specsRoot.resolve("SKILL-83-home-u-s-png/spec.md"), pathOnly)
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

  private fun writeStoreSpec(text: String): Path {
    val storeSpec = specsRoot.resolve("SKILL-77-table-criteria/spec.md")
    Files.createDirectories(storeSpec.parent)
    Files.writeString(storeSpec, text)
    return storeSpec
  }

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
