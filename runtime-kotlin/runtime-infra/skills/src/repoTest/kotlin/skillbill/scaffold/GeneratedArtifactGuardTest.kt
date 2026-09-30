package skillbill.scaffold

import skillbill.infrastructure.skills.scaffold.pointer.validateGeneratedArtifactGuard
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeneratedArtifactGuardTest {
  private val tempRoot: Path = Files.createTempDirectory("skillbill-generated-guard-")

  @AfterTest
  fun cleanup() {
    if (Files.exists(tempRoot)) {
      Files.walk(tempRoot).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
      }
    }
  }

  @Test
  fun `guard rejects generated cursor agent addon output`() {
    val repoRoot = tempRoot.resolve("agent-addon-cursor-output")
    val addon = repoRoot.resolve("agent-addons/review-helper/cursor-agents")
    Files.createDirectories(addon)
    Files.writeString(addon.resolve("bill-review-helper-worker.md"), "generated")

    val report = validateGeneratedArtifactGuard(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any { it.contains("cursor-agents") && it.contains("generated agent add-on output") },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `guard rejects generated agent addon output`() {
    val repoRoot = tempRoot.resolve("agent-addon-output")
    val addon = repoRoot.resolve("agent-addons/review-helper")
    Files.createDirectories(addon)
    Files.writeString(addon.resolve("SKILL.md"), "generated")

    val report = validateGeneratedArtifactGuard(repoRoot)

    assertFalse(report.passed)
    assertTrue(report.issues.single().contains("generated agent add-on output"))
  }

  @Test
  fun `guard rejects generated agent addon pointer under governed skills`() {
    val repoRoot = tempRoot.resolve("agent-addon-pointer-output")
    val skill = repoRoot.resolve("skills/skill-bill")
    Files.createDirectories(skill)
    Files.writeString(skill.resolve("agent-addon-review-helper.md"), "generated")

    val report = validateGeneratedArtifactGuard(repoRoot)

    assertFalse(report.passed)
    assertTrue(report.issues.single().contains("generated agent add-on pointer"))
  }

  @Test
  fun `guard allows nested authored file whose basename resembles an agent addon pointer`() {
    val repoRoot = tempRoot.resolve("nested-agent-addon-reference")
    val references = repoRoot.resolve("skills/skill-bill/references")
    Files.createDirectories(references)
    Files.writeString(references.resolve("agent-addon-review-helper.md"), "authored")

    val report = validateGeneratedArtifactGuard(repoRoot)

    assertTrue(report.passed, report.issues.toString())
  }

  @Test
  fun `guard remains dormant when source tree has no generated outputs`() {
    val repoRoot = tempRoot.resolve("baseline-repo")
    val skillDir = repoRoot.resolve("skills/skill-bill")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: skill-bill
      description: Authored source without generated wrapper.
      ---

      # Fixture Body
      """.trimIndent() + "\n",
    )

    val report = validateGeneratedArtifactGuard(repoRoot)

    assertTrue(report.passed, report.issues.joinToString("\n"))
  }

  @Test
  fun `guard rejects new governed SKILL_md outputs`() {
    val repoRoot = tempRoot.resolve("new-skill-repo")
    writeGovernedSkillOutput(repoRoot, "skills/bill-new-skill")

    val report = validateGeneratedArtifactGuard(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("skills/bill-new-skill/SKILL.md") &&
          it.contains("committed governed SKILL.md output is not allowed")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `guard rejects new platform pack governed SKILL_md outputs`() {
    val repoRoot = tempRoot.resolve("new-platform-pack-skill-repo")
    writeGovernedSkillOutput(
      repoRoot,
      "platform-packs/fixturepack/code-review/bill-fixturepack-code-review",
    )

    val report = validateGeneratedArtifactGuard(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("platform-packs/fixturepack/code-review/bill-fixturepack-code-review/SKILL.md") &&
          it.contains("committed governed SKILL.md output is not allowed")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `guard rejects new platform_yaml pointer files`() {
    val repoRoot = tempRoot.resolve("new-pointer-repo")
    writePointerFixture(
      repoRoot = repoRoot,
      pack = "fixturepack",
      skillRelativeDir = "code-review/bill-fixturepack-code-review",
      pointerName = "shell-ceremony.md",
    )

    val report = validateGeneratedArtifactGuard(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("platform-packs/fixturepack/code-review/bill-fixturepack-code-review/shell-ceremony.md") &&
          it.contains("committed platform.yaml pointer file is not allowed")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `guard ignores untracked local generated outputs when git index is available`() {
    val repoRoot = tempRoot.resolve("git-index-repo")
    Files.createDirectories(repoRoot)
    runGit(repoRoot, "init")
    writeGovernedSkillOutput(repoRoot, "skills/bill-tracked-generated")
    writeGovernedSkillOutput(repoRoot, "skills/bill-untracked-generated")
    runGit(repoRoot, "add", "skills/bill-tracked-generated")

    val report = validateGeneratedArtifactGuard(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("skills/bill-tracked-generated/SKILL.md") &&
          it.contains("committed governed SKILL.md output is not allowed")
      },
      report.issues.joinToString("\n"),
    )
    assertFalse(
      report.issues.any { it.contains("skills/bill-untracked-generated/SKILL.md") },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `the repository tree commits no generated review skill output`() {
    val report = validateGeneratedArtifactGuard(repositoryRoot())

    assertTrue(report.passed, report.issues.joinToString("\n"))
  }

  @Test
  fun `gitignore covers generated governed skill wrappers pointers and provider agent output`() {
    val ignored = Files.readString(repositoryRoot().resolve(".gitignore")).lines().toSet()

    listOf(
      "skills/*/SKILL.md",
      "platform-packs/*/code-review/*/SKILL.md",
      "skills/*/specialist-contract.md",
      "skills/*/review-orchestrator.md",
      "platform-packs/*/code-review/*/specialist-contract.md",
      "platform-packs/*/code-review/*/review-orchestrator.md",
      "skills/*/claude-agents/",
      "platform-packs/*/code-review/*/claude-agents/",
    ).forEach { pattern ->
      assertTrue(pattern in ignored, "'.gitignore' must ignore generated output pattern '$pattern'.")
    }
  }

  private fun repositoryRoot(): Path {
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
      if (Files.isRegularFile(current.resolve(".gitignore")) && Files.isDirectory(current.resolve("skills"))) {
        return current
      }
      current = current.parent
    }
    error("Repository root with a .gitignore and a skills/ directory not found.")
  }

  private fun writeGovernedSkillOutput(
    repoRoot: Path,
    relativeDir: String,
  ) {
    val skillDir = repoRoot.resolve(relativeDir)
    val skillName = skillDir.fileName.toString()
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: $skillName
      description: Fixture skill.
      ---

      # Fixture Body
      """.trimIndent() + "\n",
    )
    Files.writeString(skillDir.resolve("SKILL.md"), "generated wrapper\n")
  }

  private fun writePointerFixture(
    repoRoot: Path,
    pack: String,
    skillRelativeDir: String,
    pointerName: String,
  ) {
    val packRoot = repoRoot.resolve("platform-packs/$pack")
    val pointerFile = packRoot.resolve(skillRelativeDir).resolve(pointerName)
    Files.createDirectories(pointerFile.parent)
    Files.writeString(pointerFile, "../../../../shared/shell.md")
    Files.writeString(
      packRoot.resolve("platform.yaml"),
      """
      platform: $pack
      contract_version: "1.8"

      routing_signals:
        strong:
          - ".fixture"

      declared_code_review_areas: []

      declared_files:
        baseline: code-review/bill-$pack-code-review/content.md

      area_metadata: {}

      pointers:
        $skillRelativeDir:
          - name: $pointerName
            target: shared/shell.md
      """.trimIndent() + "\n",
    )
  }

  private fun runGit(
    repoRoot: Path,
    vararg args: String,
  ) {
    val process =
      ProcessBuilder(listOf("git", "-C", repoRoot.toString()) + args)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { reader -> reader.readText() }
    assertEquals(0, process.waitFor(), output)
  }
}
