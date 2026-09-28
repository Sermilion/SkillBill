package skillbill.scaffold

import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentSource
import skillbill.infrastructure.skills.nativeagent.composition.renderNativeAgentSource
import skillbill.infrastructure.skills.nativeagent.testNativeAgentCompositionContext
import skillbill.infrastructure.skills.scaffold.runtime.validation.RepoValidationRuntime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepoValidationSkillContentTest {
  @Test
  fun `repo validation preserves native agent source files`() {
    val repoRoot = Files.createTempDirectory("skillbill-native-agent-source-preservation")
    createRepoValidationSkillFixture(repoRoot)
    val nativeAgent = repoRoot.resolve("skills/skill-bill/native-agents/bill-code-review-worker.md")
    Files.createDirectories(nativeAgent.parent)
    val sourceText =
      renderNativeAgentSource(
        NativeAgentSource(
          name = "bill-code-review-worker",
          description = "Review changed code.",
          body = "Review the changed files.",
        ),
      )
    Files.writeString(nativeAgent, sourceText)

    RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertTrue(Files.exists(nativeAgent), "repo validation must not delete native-agent source files")
    assertEquals(
      sourceText,
      Files.readString(nativeAgent),
      "repo validation must not rewrite native-agent source files",
    )
  }

  @Test
  fun `repo validation rejects checked-in generated native agent artifact with source`() {
    val repoRoot = Files.createTempDirectory("skillbill-native-agent-checked-in-artifact")
    createRepoValidationSkillFixture(repoRoot)
    writeNativeAgentFixture(repoRoot.resolve("skills/skill-bill"), "bill-code-review-worker")
    val generatedArtifact = repoRoot.resolve("skills/skill-bill/cursor-agents/bill-code-review-worker.md")
    Files.createDirectories(generatedArtifact.parent)
    Files.writeString(generatedArtifact, "checked-in generated file\n")

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("cursor-agents/bill-code-review-worker.md") &&
          it.contains("must not be checked in")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects checked-in generated native agent artifact without source`() {
    val repoRoot = Files.createTempDirectory("skillbill-native-agent-orphan")
    createRepoValidationSkillFixture(repoRoot)
    val orphan = repoRoot.resolve("skills/skill-bill/codex-agents/orphan.toml")
    Files.createDirectories(orphan.parent)
    Files.writeString(orphan, "name = \"orphan\"\n")

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any { it.contains("codex-agents/orphan.toml") && it.contains("must not be checked in") },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation accepts skills content_md without sibling SKILL_md`() {
    val repoRoot = Files.createTempDirectory("skillbill-orphan-skills-content")
    val orphanDir = repoRoot.resolve("skills/bill-orphan-content")
    Files.createDirectories(orphanDir)
    Files.writeString(
      orphanDir.resolve("content.md"),
      """
      ---
      name: bill-orphan-content
      description: Authored content with no sibling generated wrapper.
      ---

      # Orphan Content

      Body that exists without a generated wrapper SKILL.md.
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.issues.any { it.contains("content.md found without sibling SKILL.md") })
  }

  @Test
  fun `repo validation reports skills SKILL_md without sibling content_md`() {
    val repoRoot = Files.createTempDirectory("skillbill-orphan-skills-wrapper")
    val orphanDir = repoRoot.resolve("skills/bill-orphan-wrapper")
    Files.createDirectories(orphanDir)
    Files.writeString(
      orphanDir.resolve("SKILL.md"),
      """
      ---
      name: bill-orphan-wrapper
      description: Wrapper without a sibling content.md to surface the reverse orphan.
      ---
      ## Descriptor
      Stub.
      ## Execution
      Stub.
      ## Ceremony
      Stub.
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("skills/bill-orphan-wrapper") &&
          it.contains("SKILL.md found without sibling content.md")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation accepts platform pack content_md without sibling SKILL_md`() {
    val repoRoot = Files.createTempDirectory("skillbill-orphan-pack-content")
    val orphanDir = repoRoot.resolve("platform-packs/orphanpack/code-review/bill-orphanpack-code-review")
    Files.createDirectories(orphanDir)
    Files.writeString(
      orphanDir.resolve("content.md"),
      """
      ---
      name: bill-orphanpack-code-review
      description: Pack-scoped authored content without a sibling wrapper.
      ---

      # Orphan Pack Content

      Body to surface the platform-packs/ orphan branch.
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.issues.any { it.contains("content.md found without sibling SKILL.md") })
  }

  @Test
  fun `repo validation reports platform pack SKILL_md without sibling content_md`() {
    val repoRoot = Files.createTempDirectory("skillbill-orphan-pack-wrapper")
    val orphanDir = repoRoot.resolve("platform-packs/orphanpack/code-review/bill-orphanpack-code-review")
    Files.createDirectories(orphanDir)
    Files.writeString(
      orphanDir.resolve("SKILL.md"),
      """
      ---
      name: bill-orphanpack-code-review
      description: Pack-scoped wrapper without a sibling content.md.
      ---
      ## Descriptor
      Stub.
      ## Execution
      Stub.
      ## Ceremony
      Stub.
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("platform-packs/orphanpack/code-review/bill-orphanpack-code-review") &&
          it.contains("SKILL.md found without sibling content.md")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation reports content_md frontmatter name mismatch`() {
    val repoRoot = Files.createTempDirectory("skillbill-content-name-mismatch")
    createRepoValidationSkillFixture(repoRoot)
    val contentFile = repoRoot.resolve("skills/skill-bill/content.md")
    Files.writeString(
      contentFile,
      """
      ---
      name: bill-wrong-name
      description: Authored content whose name disagrees with the directory.
      ---

      # Code Review Content

      Authored review guidance for the code-review baseline skill fixture.
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains(contentFile.toString()) &&
          it.contains("bill-wrong-name") &&
          it.contains("skill-bill")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects content_md without authored guidance beyond title`() {
    val repoRoot = Files.createTempDirectory("skillbill-content-empty-body")
    createRepoValidationSkillFixture(repoRoot)

    val contentFile = repoRoot.resolve("skills/bill-horizontal-fixture/content.md")
    Files.createDirectories(contentFile.parent)
    Files.writeString(
      contentFile,
      """
      ---
      name: bill-horizontal-fixture
      description: Horizontal fixture with no body.
      ---

      # Horizontal Fixture
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains(contentFile.toString()) &&
          it.contains("must include authored guidance beyond the title heading")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects unresolved placeholders in content_md`() {
    val repoRoot = Files.createTempDirectory("skillbill-content-placeholder")
    createRepoValidationSkillFixture(repoRoot)
    val contentFile = repoRoot.resolve("skills/skill-bill/content.md")
    Files.writeString(
      contentFile,
      """
      ---
      name: skill-bill
      description: Review code.
      ---

      # Code Review Content

      TODO: replace this placeholder.
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains(contentFile.toString()) &&
          it.contains("unresolved TODO/FIXME placeholder")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects generated support pointer links in content_md`() {
    val repoRoot = Files.createTempDirectory("skillbill-content-ceremony-pointer")
    createRepoValidationSkillFixture(repoRoot)
    val contentFile = repoRoot.resolve("skills/skill-bill/content.md")
    Files.writeString(
      contentFile,
      """
      ---
      name: skill-bill
      description: Review code.
      ---

      # Code Review Content

      Authored review guidance.

      For the shared telemetry contract, follow [telemetry-contract.md](telemetry-contract.md).
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains(contentFile.toString()) &&
          it.contains("generated support pointer 'telemetry-contract.md'")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects subagent runtime notes heading in content_md`() {
    val repoRoot = Files.createTempDirectory("skillbill-content-subagent-notes")
    createRepoValidationSkillFixture(repoRoot)
    val contentFile = repoRoot.resolve("skills/skill-bill/content.md")
    Files.writeString(
      contentFile,
      """
      ---
      name: skill-bill
      description: Review code.
      ---

      # Code Review Content

      Authored review guidance.

      ## Subagent Spawn Runtime Notes

      Prose that belongs in the wrapper, not in content.md.
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains(contentFile.toString()) &&
          it.contains("auto-generated subagent runtime notes heading")
      },
      report.issues.joinToString("\n"),
    )
  }
}
