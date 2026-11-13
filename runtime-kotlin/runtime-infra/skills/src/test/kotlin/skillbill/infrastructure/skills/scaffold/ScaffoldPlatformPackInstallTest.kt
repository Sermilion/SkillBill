package skillbill.infrastructure.skills.scaffold

import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.skills.install.scaffold.performScaffoldInstall
import skillbill.infrastructure.skills.install.scaffold.rollbackScaffoldInstallTargets
import skillbill.infrastructure.skills.scaffold.adapters.FileSystemScaffoldRepoValidation
import skillbill.infrastructure.skills.scaffold.adapters.FileSystemScaffoldSourceLoader
import skillbill.infrastructure.skills.scaffold.rendering.inferSkillDescription
import skillbill.infrastructure.skills.scaffold.rendering.renderContentBody
import skillbill.infrastructure.skills.scaffold.runtime.service.ScaffoldAdapterSeams
import skillbill.infrastructure.skills.scaffold.runtime.service.ScaffoldPlan
import skillbill.infrastructure.skills.scaffold.runtime.service.ScaffoldRuntimeContext
import skillbill.infrastructure.skills.scaffold.runtime.service.ScaffoldTransaction
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.TemplateContext
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.supportingFileTargets
import skillbill.infrastructure.skills.scaffold.runtime.service.scaffoldWithAdapters
import skillbill.install.model.ListedSkillNames
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.scaffold.model.ScaffoldResult
import skillbill.scaffold.policy.SKILL_KIND_AGENT_ADDON
import skillbill.scaffold.policy.platformpack.model.PlatformPackManifestRenderRequest
import skillbill.scaffold.policy.platformpack.renderPlatformPackManifest
import skillbill.testsupport.SkillClassFixtures
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScaffoldPlatformPackInstallTest {
  @Test
  fun `platform pack scaffold installs internal skills as parent sidecars`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val home = Path.of(System.getProperty("user.home"))
      Files.createDirectories(home.resolve(".codex"))

      scaffoldWithInstall(payload(repo, "platform-pack", "platform" to "java"))

      val codexSkills = home.resolve(".codex/skills")
      assertFalse(
        Files.exists(codexSkills.resolve("bill-java-code-check"), LinkOption.NOFOLLOW_LINKS),
        "platform quality-check skill must not install as a listed skill",
      )
      assertFalse(Files.exists(repo.resolve("platform-packs/java/quality-check"), LinkOption.NOFOLLOW_LINKS))
      val publicParentName = ListedSkillNames.publicName(PACK_SIDECAR_PARENT_SKILL)
      val reviewParentTarget = readSymlinkTarget(codexSkills.resolve(publicParentName))
      assertTrue(
        Files.isRegularFile(reviewParentTarget.resolve("bill-java-code-review.md")),
        "platform review baseline must install as a sidecar of $PACK_SIDECAR_PARENT_SKILL",
      )
      assertTrue(
        Files.isRegularFile(reviewParentTarget.resolve("bill-java-code-review-security.md")),
        "platform review specialists must install as sidecars of $PACK_SIDECAR_PARENT_SKILL",
      )
    }

  @Test
  fun `later staging failure rolls back an earlier owned link replacement`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val home = Path.of(System.getProperty("user.home"))
      val targetDir = Files.createDirectories(home.resolve(".codex/skills"))
      val previousTarget = Files.createDirectories(home.resolve(".skill-bill/installed-skills/bill-code-check-old"))
      val link = targetDir.resolve("bill-code-check")
      Files.createSymbolicLink(link, previousTarget)
      val txn = ScaffoldTransaction()
      val addonRoot = repo.resolve("agent-addons/test")
      val plan =
        ScaffoldPlan(
          kind = SKILL_KIND_AGENT_ADDON,
          skillName = "agent-addon-test",
          skillPath = addonRoot,
          skillFile = addonRoot.resolve("content.md"),
          contentFile = null,
          family = "",
          platform = "",
          area = "",
          isShelled = false,
          notes = emptyList(),
          agentAddonConsumers = listOf("bill-code-check", "missing-consumer"),
        )

      assertFailsWith<FileNotFoundException> {
        performScaffoldInstall(txn, plan, repo, ScaffoldRuntimeContext(home))
      }
      val errors = mutableListOf<String>()
      rollbackScaffoldInstallTargets(txn, errors)

      assertTrue(errors.isEmpty(), errors.joinToString())
      assertEquals(previousTarget, readSymlinkTarget(link))
    }

  @Test
  fun `dispatcher conflict rolls back the scaffold and keeps primary evidence when rollback also fails`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val home = Path.of(System.getProperty("user.home"))
      val targetDir = Files.createDirectories(home.resolve(".codex/skills"))
      val publicTarget = targetDir.resolve(ListedSkillNames.PUBLIC_DISPATCHER)
      Files.writeString(publicTarget, "user-owned")
      val request = payload(repo, "platform-pack", "platform" to "java")

      val result = scaffoldWithInstall(request)

      assertTrue(result.installIssues.isNotEmpty())
      assertTrue(result.createdFiles.isEmpty())
      assertTrue(result.manifestEdits.isEmpty())
      assertTrue(result.symlinks.isEmpty())
      assertTrue(result.installTargets.isEmpty())
      assertFalse(result.notes.any { it.contains("Auto-installed") })
      assertEquals("user-owned", Files.readString(publicTarget))
      assertFalse(Files.exists(repo.resolve("platform-packs/java")))

      val error =
        assertFailsWith<SkillBillRuntimeException> {
          scaffoldWithInstall(request, rollbackFailure = "injected rollback failure")
        }

      assertContains(error.message.orEmpty(), "Existing non-link target was preserved.")
      assertContains(error.message.orEmpty(), "injected rollback failure")
      assertEquals("user-owned", Files.readString(publicTarget))
      assertFalse(Files.exists(repo.resolve("platform-packs/java")))
    }
}

private fun scaffoldWithInstall(
  payload: Map<String, Any?>,
  rollbackFailure: String? = null,
): ScaffoldResult {
  val repoValidation = FileSystemScaffoldRepoValidation()
  val sourceLoader = FileSystemScaffoldSourceLoader()
  return scaffoldWithAdapters(
    payload,
    dryRun = false,
    ScaffoldAdapterSeams(
      validateScaffold = { plan, repoRoot -> repoValidation.validateScaffold(plan, repoRoot) },
      optionalBaselineLayers = { p, r, np -> repoValidation.optionalBaselineLayers(p, r, np) },
      resolveAddonConsumerSkillDirs = { p, pr, pk -> sourceLoader.resolveAddonConsumerSkillDirs(p, pr, pk) },
      performInstall = { txn, plan, repoRoot -> performScaffoldInstall(txn, plan, repoRoot) },
      rollbackInstallTargets = { txn, errors ->
        rollbackScaffoldInstallTargets(txn, errors)
        rollbackFailure?.let(errors::add)
      },
    ),
  )
}

private fun payload(
  repo: Path,
  kind: String,
  vararg pairs: Pair<String, Any?>,
): Map<String, Any?> =
  mapOf("scaffold_payload_version" to "1.0", "kind" to kind, "repo_root" to repo.toString()) + pairs

private fun seedRepo(): Path {
  val repo = Files.createTempDirectory("skillbill-scaffold-platform-install-repo")
  SkillClassFixtures.seedShippedSkillClasses(repo)
  supportingFileTargets(repo).values.forEach { target ->
    Files.createDirectories(target.parent)
    Files.writeString(target, "# ${target.fileName}\n")
  }
  seedBaseSkill(repo, "bill-code-check")
  seedBaseSkill(repo, PACK_SIDECAR_PARENT_SKILL)
  seedKmpPack(repo)
  return repo
}

private fun seedBaseSkill(
  repo: Path,
  skillName: String,
) {
  val skillDir = repo.resolve("skills").resolve(skillName)
  Files.createDirectories(skillDir)
  Files.writeString(
    skillDir.resolve("content.md"),
    """
    |---
    |name: $skillName
    |description: Test base skill.
    |---
    |
    |Test body.
    """.trimMargin(),
  )
}

private fun seedKmpPack(repo: Path) {
  val packRoot = repo.resolve("platform-packs/kmp")
  val baseline = packRoot.resolve("code-review/bill-kmp-code-review")
  Files.createDirectories(baseline)
  val context = TemplateContext("bill-kmp-code-review", "code-review", "kmp", "", "KMP")
  Files.writeString(
    packRoot.resolve("platform.yaml"),
    renderPlatformPackManifest(
      PlatformPackManifestRenderRequest(
        platform = "kmp",
        displayName = "KMP",
        strongSignals = listOf(".kt"),
        baselineContentPath = "code-review/bill-kmp-code-review/content.md",
      ),
    ),
  )
  Files.writeString(
    baseline.resolve("content.md"),
    renderContentBody(context, inferSkillDescription(context)),
  )
}

private fun readSymlinkTarget(linkPath: Path): Path {
  val target = Files.readSymbolicLink(linkPath)
  val resolved = if (target.isAbsolute) target else linkPath.parent.resolve(target)
  return resolved.toAbsolutePath().normalize()
}

private fun withIsolatedUserHome(block: () -> Unit) {
  val originalHome = System.getProperty("user.home")
  val tempHome = Files.createTempDirectory("skillbill-platform-install-home")
  try {
    System.setProperty("user.home", tempHome.toString())
    block()
  } finally {
    System.setProperty("user.home", originalHome)
  }
}
