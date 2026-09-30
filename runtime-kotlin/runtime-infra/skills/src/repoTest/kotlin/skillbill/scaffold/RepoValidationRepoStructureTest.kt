package skillbill.scaffold

import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentSource
import skillbill.infrastructure.skills.nativeagent.composition.renderNativeAgentSource
import skillbill.infrastructure.skills.nativeagent.testNativeAgentCompositionContext
import skillbill.infrastructure.skills.scaffold.runtime.validation.RepoValidationRuntime
import skillbill.infrastructure.skills.scaffold.runtime.validation.validateReadme
import skillbill.infrastructure.skills.scaffold.runtime.validation.validateSkillReferencesInFile
import skillbill.testing.repoRootFromTest
import skillbill.testing.seedConformingPlatformPack
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepoValidationRepoStructureTest {
  @Test
  fun `current repository validation passes without the retired over-engineering skill`() {
    val repoRoot = repoRootFromTest()

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertTrue(report.passed, report.issues.joinToString("\n"))
    assertFalse(
      Files.exists(repoRoot.resolve("skills/bill-over-engineering-review/content.md")),
      "retired over-engineering source must not be discoverable",
    )
  }

  @Test
  fun `repo validation reports missing governed directories`() {
    val repoRoot = Files.createTempDirectory("skillbill-empty-repo")

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.passed)
    assertTrue(report.issues.any { it.contains("skills/ directory is missing") })
    assertTrue(report.issues.any { it.contains("README.md is missing") })
  }

  @Test
  fun `repo validation reports malformed platform review skill structure`() {
    val repoRoot = Files.createTempDirectory("skillbill-invalid-review-shape")
    createRepoValidationSkillFixture(repoRoot)
    seedConformingPlatformPack(repoRoot, "invalid-review-shape")
    Files.writeString(
      repoRoot.resolve(
        "platform-packs/invalid-review-shape/code-review/" +
          "bill-invalid-review-shape-code-review-architecture/content.md",
      ),
      """
      |---
      |name: bill-invalid-review-shape-code-review-architecture
      |description: Malformed architecture specialist fixture.
      |internal-for: skill-bill
      |---
      |
      |# Malformed Architecture Specialist
      |
      |## Focus
      |
      |Missing the governed specialist skeleton.
      |
      """.trimMargin(),
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertTrue(report.issues.any { it.contains("specialist H2 sequence") }, report.issues.joinToString("\n"))
  }

  @Test
  fun `repo validation reports malformed native agent yaml as a typed platform issue`() {
    val repoRoot = Files.createTempDirectory("skillbill-invalid-native-agent-yaml")
    createRepoValidationSkillFixture(repoRoot)
    seedConformingPlatformPack(repoRoot, "invalid-native-agent-yaml")
    Files.writeString(
      repoRoot.resolve(
        "platform-packs/invalid-native-agent-yaml/code-review/" +
          "bill-invalid-native-agent-yaml-code-review/native-agents/agents.yaml",
      ),
      "agents: [\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertTrue(
      report.issues.any { issue ->
        issue.contains("invalid-native-agent-yaml") && issue.contains("Native agent composition source")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects add-ons outside platform pack ownership`() {
    val repoRoot = Files.createTempDirectory("skillbill-bad-addon")
    val addonFile = repoRoot.resolve("skills/example/addons/bad-addon.md")
    Files.createDirectories(addonFile.parent)
    Files.writeString(addonFile, "# Bad add-on\n")

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("skills/example/addons/bad-addon.md") &&
          it.contains("platform-packs/<pack>/addons/")
      },
    )
  }

  @Test
  fun `repo validation rejects feature addon pointer without manifest usage declaration`() {
    val repoRoot = Files.createTempDirectory("skillbill-undeclared-feature-addon")
    createRepoValidationSkillFixture(repoRoot)
    val packRoot = repoRoot.resolve("platform-packs/kmp")
    Files.createDirectories(packRoot.resolve("addons"))
    Files.writeString(packRoot.resolve("addons/android-compose-implementation.md"), "# Android Compose\n")
    Files.writeString(
      packRoot.resolve("platform.yaml"),
      """
      platform: kmp
      contract_version: "1.8"
      routing_signals:
        strong: ["androidMain"]
      declared_code_review_areas: []
      pointers:
        feature-task:
          - name: android-compose-implementation.md
            target: platform-packs/kmp/addons/android-compose-implementation.md
      """.trimIndent(),
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("platform-packs/kmp/platform.yaml") &&
          it.contains("feature_addon_usage.feature-task") &&
          it.contains("android-compose-implementation.md")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation does not require generated supporting pointers beside non-platform skills`() {
    val repoRoot = Files.createTempDirectory("skillbill-missing-sidecar")
    createRepoValidationSkillFixture(repoRoot)

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(
      report.issues.any { it.contains("required supporting sidecar") || it.contains("supporting sidecar") },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects extra authored files beside source skills`() {
    val repoRoot = Files.createTempDirectory("skillbill-extra-source-file")
    createRepoValidationSkillFixture(repoRoot)
    Files.writeString(repoRoot.resolve("skills/skill-bill/patterns.md"), "extra organization file\n")

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("skills/skill-bill/patterns.md") &&
          it.contains("skill source directories may contain only content.md and native-agents/")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects committed generated supporting pointer beside non-platform skills`() {
    val repoRoot = Files.createTempDirectory("skillbill-wrong-sidecar")
    val wrongTarget = repoRoot.resolve("orchestration/wrong/PLAYBOOK.md")
    Files.createDirectories(wrongTarget.parent)
    Files.writeString(wrongTarget, "wrong\n")
    createRepoValidationSkillFixture(
      repoRoot,
      overrideTargets = mapOf("shell-ceremony.md" to wrongTarget),
      writeSidecars = true,
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertTrue(
      report.issues.any {
        it.contains("skills/skill-bill/shell-ceremony.md") &&
          it.contains("committed generated supporting pointer file is not allowed")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects git symlink placeholders beside non-platform skills`() {
    val repoRoot = Files.createTempDirectory("skillbill-placeholder-sidecar")
    createRepoValidationSkillFixture(repoRoot, sidecarMode = SidecarMode.GitPlaceholder, writeSidecars = true)

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertTrue(
      report.issues.any {
        it.contains("skills/skill-bill/shell-ceremony.md") &&
          it.contains("committed generated supporting pointer file is not allowed")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects regular copied generated supporting pointer files beside non-platform skills`() {
    val repoRoot = Files.createTempDirectory("skillbill-regular-sidecar")
    createRepoValidationSkillFixture(repoRoot)
    val sidecar = repoRoot.resolve("skills/skill-bill/shell-ceremony.md")
    Files.writeString(sidecar, "copied markdown\n")

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertTrue(
      report.issues.any {
        it.contains("skills/skill-bill/shell-ceremony.md") &&
          it.contains("committed generated supporting pointer file is not allowed")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation skips native agent markdown skill references`() {
    val repoRoot = Files.createTempDirectory("skillbill-native-agent-refs")
    createRepoValidationSkillFixture(repoRoot)
    val nativeAgent = repoRoot.resolve("skills/skill-bill/native-agents/bill-code-review-worker.md")
    Files.createDirectories(nativeAgent.parent)
    Files.writeString(
      nativeAgent,
      renderNativeAgentSource(
        NativeAgentSource(
          name = "bill-code-review-worker",
          description = "Review changed code.",
          body = "Mentions bill-code-review-worker inside native agent prose.",
        ),
      ),
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(
      report.issues.any { it.contains("references unknown skill 'bill-code-review-worker'") },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation reads the skill-bill slash command as a skill but not skill-bill cli prose`() {
    val repoRoot = Files.createTempDirectory("skillbill-dispatcher-refs")
    val skillFile = repoRoot.resolve("skills/bill-example/content.md")
    Files.createDirectories(skillFile.parent)
    Files.writeString(
      skillFile,
      "Run `/skill-bill SKILL-1`, or `skill-bill goal SKILL-1` from ~/.local/bin/skill-bill.\n",
    )
    val readme = repoRoot.resolve("README.md")
    Files.writeString(readme, "| Skill | Use |\n| --- | --- |\n| `/skill-bill` | Dispatcher. |\n")
    val issues = mutableListOf<String>()

    validateSkillReferencesInFile(skillFile, repoRoot, setOf("bill-example"), issues)
    validateReadme(readme, setOf("skill-bill"), emptySet(), issues)

    assertEquals(listOf("skills/bill-example/content.md: references unknown skill 'skill-bill'"), issues)
  }

  @Test
  fun `repo validation skips boundary ledger references to deleted skills`() {
    val repoRoot = Files.createTempDirectory("skillbill-boundary-ledger-refs")
    createRepoValidationSkillFixture(repoRoot)
    val ledger = repoRoot.resolve("skills/agent/history.md")
    Files.createDirectories(ledger.parent)
    Files.writeString(ledger, "Areas: skills/legacy-feature-prose, skills/legacy-feature-runner\n")

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(
      report.issues.any { it.contains("references unknown skill 'legacy-feature-prose'") },
      report.issues.joinToString("\n"),
    )
    assertFalse(
      report.issues.any { it.contains("references unknown skill 'legacy-feature-runner'") },
      report.issues.joinToString("\n"),
    )
  }
}
