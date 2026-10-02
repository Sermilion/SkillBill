package skillbill.infrastructure.skills.install

import skillbill.install.model.InstallAgentLinkStatus
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.install.model.SupportedAgent
import skillbill.model.toPath
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallApplyReplacementCleanupTest : InstallApplyTestSupport() {
  @Test
  fun `apply replacement removes previously installed unselected platform links`() {
    val fixture = setupApplyFixture()
    val selectedPlatformPlan =
      planInstallForTest(
        fixture.request(
          selectedPlatforms = setOf("kotlin"),
          agents = setOf(SupportedAgent.CODEX),
        ),
      )
    applyInstallForTest(selectedPlatformPlan)
    val targetDir = fixture.home.resolve("agent-skill-targets/codex")
    val sidecarParentStaging = readSymlinkTarget(targetDir.resolve(PACK_SIDECAR_PARENT_SKILL))
    assertTrue(
      Files.isRegularFile(sidecarParentStaging.resolve("bill-kotlin-code-review.md")),
    )
    assertFalse(Files.exists(targetDir.resolve("bill-kotlin-code-check"), LinkOption.NOFOLLOW_LINKS))
    assertFalse(
      Files.exists(
        sidecarParentStaging.resolve("bill-kotlin-code-check.md"),
        LinkOption.NOFOLLOW_LINKS,
      ),
    )

    val baseOnlyReplacementPlan =
      planInstallForTest(
        fixture.request(
          agents = setOf(SupportedAgent.CODEX),
          replaceExistingSkillBillLinks = true,
        ),
      )

    val result = applyInstallForTest(baseOnlyReplacementPlan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertTrue(Files.isSymbolicLink(targetDir.resolve("bill-code-review")))
    assertTrue(Files.isSymbolicLink(targetDir.resolve("bill-code-check")))
    assertFalse(Files.exists(targetDir.resolve("bill-kotlin-code-review"), LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(targetDir.resolve("bill-kotlin-code-check"), LinkOption.NOFOLLOW_LINKS))
    assertFalse(
      Files.exists(
        targetDir.resolve("$PACK_SIDECAR_PARENT_SKILL/bill-kotlin-code-review.md"),
        LinkOption.NOFOLLOW_LINKS,
      ),
    )
  }

  @Test
  fun `apply replacement upgrades existing Skill Bill source symlinks before linking staged skills`() {
    val fixture = setupApplyFixture()
    val targetDir = fixture.home.resolve("agent-skill-targets/codex")
    Files.createDirectories(targetDir)
    val legacySourceLink = targetDir.resolve("bill-code-review")
    createSymlinkOrSkip(legacySourceLink, fixture.repoRoot.resolve("skills/bill-code-review"))
    val legacyManagedDir = targetDir.resolve("bill-code-check")
    Files.createDirectories(legacyManagedDir)
    Files.writeString(legacyManagedDir.resolve(".skill-bill-install"), "")
    Files.writeString(legacyManagedDir.resolve("SKILL.md"), "old managed install")
    val plan =
      planInstallForTest(
        fixture.request(
          agents = setOf(SupportedAgent.CODEX),
          replaceExistingSkillBillLinks = true,
        ),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertTrue(Files.isSymbolicLink(legacySourceLink))
    assertTrue(readSymlinkTarget(legacySourceLink).startsWith(fixture.home.resolve(".skill-bill/installed-skills")))
    assertTrue(Files.isSymbolicLink(legacyManagedDir))
    assertTrue(readSymlinkTarget(legacyManagedDir).startsWith(fixture.home.resolve(".skill-bill/installed-skills")))
    val billCodeReviewLinks =
      result.skills
        .single { skill -> skill.skillName == "bill-code-review" }
        .links
    assertTrue(
      billCodeReviewLinks.any { link ->
        link.agent == SupportedAgent.CODEX && link.status == InstallAgentLinkStatus.CREATED
      },
    )
  }

  @Test
  fun `apply migrates a clone-pointing managed link onto the copy with no dangling clone link`() {
    val fixture = setupApplyFixture()
    val clone = Files.createTempDirectory("skillbill-install-apply-clone").also(tempDirs::add)
    seedBaseSkill(clone, "bill-code-review")
    val targetDir = fixture.home.resolve("agent-skill-targets/codex")
    Files.createDirectories(targetDir)
    val cloneManagedLink = targetDir.resolve("bill-code-review")
    createSymlinkOrSkip(cloneManagedLink, clone.resolve("skills/bill-code-review"))

    val plan =
      planInstallForTest(
        fixture.request(
          agents = setOf(SupportedAgent.CODEX),
          replaceExistingSkillBillLinks = true,
        ),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertTrue(Files.isSymbolicLink(cloneManagedLink), "managed link must survive as a symlink")
    val repointed = readSymlinkTarget(cloneManagedLink)
    assertTrue(
      repointed.startsWith(fixture.home.resolve(".skill-bill/installed-skills")),
      "managed link must repoint under the copied repoRoot staging cache, was $repointed",
    )
    assertFalse(
      repointed.startsWith(clone.toAbsolutePath().normalize()),
      "managed link must NOT keep resolving into the clone, was $repointed",
    )

    val cloneRoot = clone.toAbsolutePath().normalize()
    val danglingIntoClone =
      Files.list(targetDir).use { stream ->
        stream
          .filter { entry -> Files.isSymbolicLink(entry) }
          .filter { entry -> readSymlinkTarget(entry).startsWith(cloneRoot) }
          .toList()
      }
    assertTrue(danglingIntoClone.isEmpty(), "no symlink may still point into the clone: $danglingIntoClone")
  }

  @Test
  fun `apply replacement removes legacy renamed Skill Bill links`() {
    val fixture = setupApplyFixture()
    val targetDir = fixture.home.resolve("agent-skill-targets/codex")
    Files.createDirectories(targetDir)
    val legacyPaths = createLegacyRenamedLinks(fixture, targetDir)
    val plan =
      planInstallForTest(
        fixture.request(
          agents = setOf(SupportedAgent.CODEX),
          replaceExistingSkillBillLinks = true,
        ),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    legacyPaths.forEach { path ->
      assertFalse(Files.exists(path, LinkOption.NOFOLLOW_LINKS), "$path should be removed")
    }
    assertTrue(Files.isSymbolicLink(targetDir.resolve("bill-code-review")))
    assertTrue(Files.isSymbolicLink(targetDir.resolve("bill-code-check")))
  }

  private fun createLegacyRenamedLinks(
    fixture: ApplyFixture,
    targetDir: Path,
  ): List<Path> {
    val links =
      listOf(
        "bill-backend-kotlin-code-review" to "platform-packs/kotlin/code-review/bill-kotlin-code-review",
        "bill-quality-check" to "skills/bill-code-check",
        "bill-code-quality-check" to "skills/bill-code-check",
        "bill-kotlin-quality-check" to "platform-packs/kotlin/quality-check/bill-kotlin-code-check",
        "bill-kotlin-code-quality-check" to "platform-packs/kotlin/quality-check/bill-kotlin-code-check",
        ("bill-feature-" + "implement") to "skills/bill-feature",
        ("bill-feature-" + "implement-agentic") to "skills/bill-feature",
        "bill-new-skill-all-agents" to "skills/bill-create-skill",
        "bill-grill-plan" to "skills/bill-grill-plan",
        "bill-skill-remove" to "skills/bill-skill-remove",
      ).map { (linkName, sourcePath) ->
        targetDir.resolve(linkName).also { link ->
          createSymlinkOrSkip(link, fixture.repoRoot.resolve(sourcePath))
        }
      }
    val managedDirs =
      listOf(
        targetDir.resolve("mdp-gcheck"),
        targetDir.resolve("mdp-skill-scaffold"),
      ).onEach { managedDir ->
        Files.createDirectories(managedDir)
        Files.writeString(managedDir.resolve(".skill-bill-install"), "")
        Files.writeString(managedDir.resolve("SKILL.md"), "old managed install")
      }
    return links + managedDirs
  }

  @Test
  fun `apply surfaces replacement cleanup failures as structured link failures`() {
    val fixture = setupApplyFixture()
    val targetDir = fixture.home.resolve("agent-skill-targets/codex")
    Files.createDirectories(targetDir)
    val plan =
      planInstallForTest(
        fixture.request(
          agents = setOf(SupportedAgent.CODEX),
          replaceExistingSkillBillLinks = true,
        ),
      )
    val invalidName = "bad\u0000name"
    val tampered =
      plan.copy(
        skills =
          plan.skills.map { skill ->
            if (skill.name == "bill-code-review") skill.copy(name = invalidName) else skill
          },
      )

    val result = applyInstallForTest(tampered)

    assertEquals(InstallApplyStatus.FAILURE, result.status)
    assertTrue(
      result.failures.any { failure ->
        failure.kind == InstallApplyIssueKind.SKILL_LINK_FAILED &&
          failure.agent == SupportedAgent.CODEX &&
          failure.path?.toPath() == targetDir &&
          failure.message.contains("Nul character")
      },
    )
  }
}
