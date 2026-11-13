package skillbill.infrastructure.skills.install

import skillbill.install.model.InstallAgentLinkStatus
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.ListedSkillNames
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
  fun `public dispatcher conflict preserves user file and the old owned link`() {
    val fixture = setupApplyFixture()
    val targetDir = fixture.home.resolve("agent-skill-targets/codex")
    Files.createDirectories(targetDir)
    val publicTarget = targetDir.resolve(ListedSkillNames.PUBLIC_DISPATCHER)
    Files.writeString(publicTarget, "user-owned")
    val oldStaging = Files.createDirectories(fixture.home.resolve(".skill-bill/installed-skills/skill-bill-old"))
    val oldLink = targetDir.resolve(ListedSkillNames.DISPATCHER)
    createSymlinkOrSkip(oldLink, oldStaging)
    val plan =
      planInstallForTest(
        fixture.request(
          agents = setOf(SupportedAgent.CODEX),
          replaceExistingSkillBillLinks = true,
        ),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.FAILURE, result.status)
    assertTrue(result.failures.any { issue -> issue.kind == InstallApplyIssueKind.SKILL_LINK_FAILED })
    assertEquals("user-owned", Files.readString(publicTarget))
    assertTrue(Files.isSymbolicLink(oldLink))
    assertEquals(oldStaging.toAbsolutePath().normalize(), readSymlinkTarget(oldLink))
  }

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
    val publicParentName = ListedSkillNames.publicName(PACK_SIDECAR_PARENT_SKILL)
    val sidecarParentStaging = readSymlinkTarget(targetDir.resolve(publicParentName))
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
        targetDir.resolve("$publicParentName/bill-kotlin-code-review.md"),
        LinkOption.NOFOLLOW_LINKS,
      ),
    )
  }

  @Test
  fun `apply replacement preserves source symlinks and existing non-link targets and reports failure`() {
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

    assertEquals(InstallApplyStatus.FAILURE, result.status)
    assertTrue(Files.isSymbolicLink(legacySourceLink))
    assertEquals(fixture.repoRoot.resolve("skills/bill-code-review"), readSymlinkTarget(legacySourceLink))
    assertEquals("old managed install", Files.readString(legacyManagedDir.resolve("SKILL.md")))
    val billCodeReviewLinks =
      result.skills
        .single { skill -> skill.skillName == "bill-code-review" }
        .links
    assertTrue(
      billCodeReviewLinks.any { link ->
        link.agent == SupportedAgent.CODEX && link.status == InstallAgentLinkStatus.FAILED
      },
    )
  }

  @Test
  fun `apply replaces an owned stale link and repeat application keeps its payload usable`() {
    val fixture = setupApplyFixture()
    val cacheRoot = fixture.home.resolve(".skill-bill/installed-skills")
    val oldStaging = Files.createDirectories(cacheRoot.resolve("bill-code-review-deadbeefdeadbeef"))
    Files.writeString(oldStaging.resolve("SKILL.md"), "prior usable payload")
    val targetDir = fixture.home.resolve("agent-skill-targets/codex")
    Files.createDirectories(targetDir)
    val managedLink = targetDir.resolve("bill-code-review")
    createSymlinkOrSkip(managedLink, oldStaging)

    val plan =
      planInstallForTest(
        fixture.request(
          agents = setOf(SupportedAgent.CODEX),
          replaceExistingSkillBillLinks = true,
        ),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val repointed = readSymlinkTarget(managedLink)
    assertTrue(repointed.startsWith(cacheRoot))
    assertTrue(repointed != oldStaging)
    assertTrue(Files.isRegularFile(managedLink.resolve("SKILL.md")))
    assertEquals("prior usable payload", Files.readString(oldStaging.resolve("SKILL.md")))

    val repeated = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, repeated.status)
    assertEquals(repointed, readSymlinkTarget(managedLink))
    assertTrue(Files.isRegularFile(managedLink.resolve("SKILL.md")))
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
        "bill-backend-kotlin-code-review",
        "bill-quality-check",
        "bill-code-quality-check",
        "bill-kotlin-quality-check",
        "bill-kotlin-code-quality-check",
        "bill-feature-" + "implement",
        "bill-feature-" + "implement-agentic",
        "bill-new-skill-all-agents",
        "bill-grill-plan",
        "bill-skill-remove",
      ).map { linkName ->
        targetDir.resolve(linkName).also { link ->
          val ownedCache = fixture.home.resolve(".skill-bill/installed-skills/$linkName-deadbeefdeadbeef")
          createSymlinkOrSkip(link, ownedCache)
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
  fun `apply preserves an unowned legacy alias and reports it without failing the install`() {
    val fixture = setupApplyFixture()
    val targetDir = Files.createDirectories(fixture.home.resolve("agent-skill-targets/codex"))
    val legacyLink = targetDir.resolve("bill-quality-check")
    val externalTarget = fixture.repoRoot.resolve("skills/bill-code-check")
    createSymlinkOrSkip(legacyLink, externalTarget)
    val plan =
      planInstallForTest(
        fixture.request(agents = setOf(SupportedAgent.CODEX), replaceExistingSkillBillLinks = true),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.WARNING, result.status)
    assertTrue(result.failures.isEmpty())
    assertEquals(externalTarget, readSymlinkTarget(legacyLink))
    assertTrue(
      result.warnings.any { issue ->
        issue.kind == InstallApplyIssueKind.SKILL_LINK_FAILED && issue.path?.toPath() == legacyLink
      },
    )
  }

  @Test
  fun `apply removes a legacy link into an old source checkout`() {
    val fixture = setupApplyFixture()
    val targetDir = Files.createDirectories(fixture.home.resolve("agent-skill-targets/codex"))
    val legacyLink = targetDir.resolve("bill-quality-check")
    val deletedCheckoutSkill = fixture.home.resolve("Development/skill-bill/skills/bill-quality-check")
    createSymlinkOrSkip(legacyLink, deletedCheckoutSkill)
    val plan =
      planInstallForTest(
        fixture.request(agents = setOf(SupportedAgent.CODEX), replaceExistingSkillBillLinks = true),
      )

    val result = applyInstallForTest(plan)

    assertTrue(result.failures.isEmpty())
    assertTrue(result.warnings.none { issue -> issue.path?.toPath() == legacyLink })
    assertFalse(Files.exists(legacyLink, LinkOption.NOFOLLOW_LINKS))
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
    val invalidName = "bill-bad\u0000name"
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
