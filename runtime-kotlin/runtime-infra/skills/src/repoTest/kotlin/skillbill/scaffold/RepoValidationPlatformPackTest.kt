package skillbill.scaffold

import skillbill.infrastructure.skills.nativeagent.testNativeAgentCompositionContext
import skillbill.infrastructure.skills.scaffold.runtime.validation.RepoValidationRuntime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepoValidationPlatformPackTest {
  @Test
  fun `repo validation rejects generated wrapper boilerplate headings in content_md`() {
    val repoRoot = Files.createTempDirectory("skillbill-content-wrapper-boilerplate")
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

      ## Descriptor

      Generated wrapper metadata does not belong in source content.
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains(contentFile.toString()) &&
          it.contains("generated wrapper boilerplate heading '## Descriptor'")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation does not require governed SKILL_md drift files on disk`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-drift-wiring")
    val skillDir = repoRoot.resolve("skills/bill-runtime-drift")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: bill-runtime-drift
      description: Runtime drift wiring fixture.
      ---

      # Runtime Drift Fixture
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.issues.any { it.contains("governed SKILL.md output drifted") }, report.issues.joinToString("\n"))
  }

  @Test
  fun `repo validation includes generated artifact guard issues`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-guard-wiring")
    createRepoValidationSkillFixture(repoRoot)
    val generatedSkillDir = repoRoot.resolve("skills/bill-new-generated")
    Files.createDirectories(generatedSkillDir)
    Files.writeString(
      generatedSkillDir.resolve("content.md"),
      """
      ---
      name: bill-new-generated
      description: New generated wrapper fixture.
      ---

      # New Generated Fixture
      """.trimIndent() + "\n",
    )
    Files.writeString(generatedSkillDir.resolve("SKILL.md"), "generated wrapper\n")

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("skills/bill-new-generated/SKILL.md") &&
          it.contains("committed governed SKILL.md output is not allowed")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects checked-in generated cursor native agent artifact`() {
    val repoRoot = Files.createTempDirectory("skillbill-native-agent-cursor-checked-in")
    createRepoValidationSkillFixture(repoRoot)
    writeNativeAgentFixture(repoRoot.resolve("skills/skill-bill"), "bill-code-review-worker")
    val generatedCursor = repoRoot.resolve("skills/skill-bill/cursor-agents/bill-code-review-worker.md")
    Files.createDirectories(generatedCursor.parent)
    Files.writeString(generatedCursor, "checked-in cursor file\n")

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
  fun `repo validation rejects checked-in generated junie native agent artifact`() {
    val repoRoot = Files.createTempDirectory("skillbill-native-agent-junie-checked-in")
    createRepoValidationSkillFixture(repoRoot)
    writeNativeAgentFixture(repoRoot.resolve("skills/skill-bill"), "bill-code-review-worker")
    val generatedJunie = repoRoot.resolve("skills/skill-bill/junie-agents/bill-code-review-worker.md")
    Files.createDirectories(generatedJunie.parent)
    Files.writeString(generatedJunie, "checked-in junie file\n")

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("junie-agents/bill-code-review-worker.md") &&
          it.contains("must not be checked in")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects bare orchestration path token in skill content_md`() {
    val repoRoot = Files.createTempDirectory("skillbill-orchestration-path-in-skill")
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

      See `orchestration/contracts/some-schema.yaml` for detail.
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("skills/skill-bill/content.md") &&
          it.contains("orchestration/contracts/some-schema.yaml")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation passes when skill content_md contains no bare orchestration paths`() {
    val repoRoot = Files.createTempDirectory("skillbill-no-orchestration-path-in-skill")
    createRepoValidationSkillFixture(repoRoot)

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(
      report.issues.any { it.contains("must not reference bare orchestration path") },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation applies portable review wording lint to non kotlin pack baselines from manifests`() {
    val repoRoot = Files.createTempDirectory("skillbill-portable-review-python")
    createRepoValidationSkillFixture(repoRoot)
    seedPlatformReviewPack(
      repoRoot = repoRoot,
      slug = "python",
      body = "Agent to spawn: security reviewer.",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertTrue(
      report.issues.any {
        it.contains("platform-packs/python/code-review/bill-python-code-review/content.md") &&
          it.contains("must use portable specialist-review wording")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation keeps portable review wording lint for kotlin and kmp manifest baselines`() {
    val repoRoot = Files.createTempDirectory("skillbill-portable-review-kotlin-kmp")
    createRepoValidationSkillFixture(repoRoot)
    seedPlatformReviewPack(
      repoRoot = repoRoot,
      slug = "kotlin",
      body = "Agent to spawn: security reviewer.",
    )
    seedPlatformReviewPack(
      repoRoot = repoRoot,
      slug = "kmp",
      body = "sub-agent review lane.",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertTrue(
      report.issues.any {
        it.contains("platform-packs/kotlin/code-review/bill-kotlin-code-review/content.md") &&
          it.contains("must use portable specialist-review wording")
      },
      report.issues.joinToString("\n"),
    )
    assertTrue(
      report.issues.any {
        it.contains("platform-packs/kmp/code-review/bill-kmp-code-review/content.md") &&
          it.contains("must not describe review delegation as sub-agents")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation accepts a valid internal-for declaration`() {
    val repoRoot = Files.createTempDirectory("skillbill-valid-internal")
    createRepoValidationSkillFixture(repoRoot)
    seedInternalSkill(repoRoot, "bill-feature-helper", "skill-bill")

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    val internalIssues = report.issues.filter { it.contains("internal skill") }
    assertTrue(internalIssues.isEmpty(), "expected no internal-skill issues; got: ${internalIssues.joinToString("\n")}")
  }
}
