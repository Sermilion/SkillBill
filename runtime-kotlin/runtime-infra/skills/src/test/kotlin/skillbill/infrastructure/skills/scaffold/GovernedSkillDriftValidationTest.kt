package skillbill.infrastructure.skills.scaffold

import skillbill.infrastructure.skills.scaffold.authoring.AuthoringRenderBlock
import skillbill.infrastructure.skills.scaffold.authoring.AuthoringRenderResult
import skillbill.infrastructure.skills.scaffold.authoring.renderWrapper
import skillbill.infrastructure.skills.scaffold.authoring.resolveTarget
import skillbill.infrastructure.skills.scaffold.runtime.validation.validateGovernedSkillDrift
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GovernedSkillDriftValidationTest {
  private val tempRoot: Path = Files.createTempDirectory("skillbill-governed-drift-")

  @AfterTest
  fun cleanup() {
    if (Files.exists(tempRoot)) {
      Files.walk(tempRoot).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
      }
    }
  }

  @Test
  fun `drift validation discovers every governed skill target`() {
    val repoRoot = tempRoot.resolve("multi-skill-repo")
    writeRenderedHorizontalSkill(repoRoot, "bill-alpha")
    writeRenderedHorizontalSkill(repoRoot, "bill-beta")

    val report = validateGovernedSkillDrift(repoRoot)

    assertEquals(2, report.skillCount)
    assertTrue(report.passed, report.issues.joinToString("\n"))
  }

  @Test
  fun `drift validation reports parse and render failures with target path`() {
    val repoRoot = tempRoot.resolve("bad-content-repo")
    val skillDir = repoRoot.resolve("skills/bill-bad-content")
    Files.createDirectories(skillDir)
    Files.writeString(skillDir.resolve("content.md"), "# Missing frontmatter\n")

    val report = validateGovernedSkillDrift(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("skills/bill-bad-content/content.md") &&
          it.contains("cannot render governed skill 'bill-bad-content'")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `drift validation reports unresolved platform pointer targets`() {
    val repoRoot = writePlatformSkillWithPointer(targetExists = false)

    val report = validateGovernedSkillDrift(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("platform-packs/fixturepack/code-review/bill-fixturepack-code-review/shell-ceremony.md") &&
          it.contains("cannot resolve platform.yaml pointer target")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `drift validation does not compare rendered output to source tree SKILL_md`() {
    val repoRoot = tempRoot.resolve("stale-wrapper-repo")
    val skillDir = repoRoot.resolve("skills/bill-stale-wrapper")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: bill-stale-wrapper
      description: Fixture skill with stale generated wrapper.
      ---

      # Fresh Authored Body
      """.trimIndent() + "\n",
    )

    val report = validateGovernedSkillDrift(repoRoot)

    assertTrue(report.passed, report.issues.joinToString("\n"))
  }

  @Test
  fun `drift validation reports non byte identical repeated rendering`() {
    val repoRoot = tempRoot.resolve("non-deterministic-repo")
    writeRenderedHorizontalSkill(repoRoot, "bill-nondeterministic")
    var renderCount = 0

    val report =
      validateGovernedSkillDrift(repoRoot) { root, target ->
        renderCount += 1
        AuthoringRenderResult(
          repoRoot = root,
          skillName = target.skillName,
          blocks =
            listOf(
              AuthoringRenderBlock(
                header = "===== SKILL.md: skills/bill-nondeterministic/SKILL.md =====",
                content = "render-$renderCount\n",
              ),
            ),
        )
      }

    assertFalse(report.passed)
    assertTrue(
      report.issues.any { it.contains("render is not byte-identical when repeated in memory") },
      report.issues.joinToString("\n"),
    )
  }

  private fun writeRenderedHorizontalSkill(
    repoRoot: Path,
    skillName: String,
  ) {
    val skillDir = repoRoot.resolve("skills/$skillName")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: $skillName
      description: Fixture skill for governed drift validation.
      ---

      # Fixture Body
      """.trimIndent() + "\n",
    )
    val target = resolveTarget(repoRoot, skillName)
    renderWrapper(target)
  }

  private fun writePlatformSkillWithPointer(targetExists: Boolean): Path {
    val repoRoot = tempRoot.resolve("platform-pointer-repo")
    val packRoot = repoRoot.resolve("platform-packs/fixturepack")
    val skillDir = packRoot.resolve("code-review/bill-fixturepack-code-review")
    Files.createDirectories(skillDir)
    if (targetExists) {
      Files.createDirectories(repoRoot.resolve("shared"))
      Files.writeString(repoRoot.resolve("shared/shell.md"), "# shell\n")
    }
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: bill-fixturepack-code-review
      description: Fixture platform skill.
      ---

      # Fixture Platform Body
      """.trimIndent() + "\n",
    )
    Files.writeString(packRoot.resolve("platform.yaml"), platformManifest())
    val target = resolveTarget(repoRoot, "bill-fixturepack-code-review")
    renderWrapper(target)
    return repoRoot
  }

  private fun platformManifest(): String =
    """
    platform: fixturepack
    contract_version: "1.8"

    routing_signals:
      strong:
        - ".fixture"

    declared_code_review_areas: []

    declared_files:
      baseline: code-review/bill-fixturepack-code-review/content.md

    area_metadata: {}

    pointers:
      code-review/bill-fixturepack-code-review:
        - name: shell-ceremony.md
          target: shared/shell.md
    """.trimIndent() + "\n"
}
