package skillbill.install

import skillbill.infrastructure.skills.install.ApplyFixture
import skillbill.infrastructure.skills.install.InstallApplyTestSupport
import skillbill.infrastructure.skills.install.applyInstallForTest
import skillbill.infrastructure.skills.install.planInstallForTest
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.SupportedAgent
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetiredSkillInstallCleanupRepoTest : InstallApplyTestSupport() {
  @Test
  fun `installing over an old home removes every retired skill link and cached copy`() {
    val home = Files.createTempDirectory("skillbill-retired-install-home").also(tempDirs::add)
    val cacheRoot = home.resolve(".skill-bill/installed-skills")
    val claudeAgentsDir = Files.createDirectories(home.resolve(".claude/agents"))
    val fixture = ApplyFixture(repoRootFromTest(), home)
    val agents = setOf(SupportedAgent.CLAUDE, SupportedAgent.CODEX)
    val targetDirs = agents.map { agent -> Files.createDirectories(home.resolve("agent-skill-targets/${agent.id}")) }

    val cachedCopies = retiredSkillNames.map { name -> seedCachedCopy(cacheRoot, name) }
    val oldCloneSkills = home.resolve("old-clone/skills")
    val seededLinks =
      targetDirs.flatMap { targetDir ->
        retiredSkillNames.flatMap { name ->
          listOf(
            targetDir.resolve(name).also { link -> createSymlinkOrSkip(link, oldCloneSkills.resolve(name)) },
            seedManagedDir(targetDir.resolve("mdp-${name.removePrefix("bill-")}")),
          )
        }
      }
    val inlineNativeAgentLink = seedInlineNativeAgentLink(cacheRoot, claudeAgentsDir)

    val result =
      applyInstallForTest(
        planInstallForTest(fixture.request(agents = agents, replaceExistingSkillBillLinks = true)),
      )

    assertEquals(InstallApplyStatus.SUCCESS, result.status, "install failures: ${result.failures}")
    (seededLinks + cachedCopies + inlineNativeAgentLink).forEach { path ->
      assertFalse(Files.exists(path, LinkOption.NOFOLLOW_LINKS), "$path should be removed")
    }
    targetDirs.forEach { targetDir ->
      val skillBillLink = targetDir.resolve("skill-bill")
      assertTrue(Files.isSymbolicLink(skillBillLink), "$skillBillLink should link the skill-bill skill")
      assertTrue(readSymlinkTarget(skillBillLink).startsWith(cacheRoot.toAbsolutePath().normalize()))
    }
  }

  private fun cachedCopy(
    cacheRoot: Path,
    skillName: String,
  ): Path = cacheRoot.resolve("$skillName-$OLD_CACHE_HASH")

  private fun seedCachedCopy(
    cacheRoot: Path,
    skillName: String,
  ): Path =
    Files.createDirectories(cachedCopy(cacheRoot, skillName)).also { copy ->
      Files.writeString(copy.resolve("SKILL.md"), content(skillName))
    }

  private fun seedManagedDir(managedDir: Path): Path =
    Files.createDirectories(managedDir).also { dir ->
      Files.writeString(dir.resolve(".skill-bill-install"), "")
      Files.writeString(dir.resolve("SKILL.md"), "old managed install")
    }

  private fun seedInlineNativeAgentLink(
    cacheRoot: Path,
    claudeAgentsDir: Path,
  ): Path {
    val artifactDir = Files.createDirectories(cacheRoot.resolve("native-agents-$OLD_CACHE_HASH/claude-agents"))
    val artifact = artifactDir.resolve("$INLINE_NATIVE_AGENT.md")
    Files.writeString(artifact, "---\nname: $INLINE_NATIVE_AGENT\ndescription: Old inline worker.\n---\n\nReview.\n")
    return claudeAgentsDir.resolve("$INLINE_NATIVE_AGENT.md").also { link -> createSymlinkOrSkip(link, artifact) }
  }

  private companion object {
    const val OLD_CACHE_HASH = "0123456789abcdef"
    const val INLINE_NATIVE_AGENT = "bill-code-review-inline"

    val retiredSkillNames =
      listOf(
        "bill-feature",
        "bill-feature-spec",
        "bill-code-review",
        "bill-code-review-inline",
        "bill-code-check",
        "bill-pr-description",
        "bill-boundary-history",
        "bill-boundary-decisions",
        "bill-pr-review-fix",
        "bill-unit-test-value-check",
        "bill-update-check",
        "bill-release",
        "bill-feature-verify",
        "bill-feature-guard",
        "bill-feature-guard-cleanup",
        "bill-monitor",
      )
  }
}
