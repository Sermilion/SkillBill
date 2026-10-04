package skillbill.infrastructure.skills.scaffold

import skillbill.infrastructure.skills.install.scaffold.performScaffoldInstall
import skillbill.infrastructure.skills.install.scaffold.rollbackScaffoldInstallTargets
import skillbill.infrastructure.skills.scaffold.adapters.FileSystemScaffoldRepoValidation
import skillbill.infrastructure.skills.scaffold.adapters.FileSystemScaffoldSourceLoader
import skillbill.infrastructure.skills.scaffold.rendering.inferSkillDescription
import skillbill.infrastructure.skills.scaffold.rendering.renderContentBody
import skillbill.infrastructure.skills.scaffold.runtime.service.ScaffoldAdapterSeams
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.TemplateContext
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.supportingFileTargets
import skillbill.infrastructure.skills.scaffold.runtime.service.performInstall
import skillbill.infrastructure.skills.scaffold.runtime.service.scaffoldWithAdapters
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.scaffold.policy.platformpack.model.PlatformPackManifestRenderRequest
import skillbill.scaffold.policy.platformpack.renderPlatformPackManifest
import skillbill.testsupport.SkillClassFixtures
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.Test
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
      val reviewParentTarget = readSymlinkTarget(codexSkills.resolve(PACK_SIDECAR_PARENT_SKILL))
      assertTrue(
        Files.isRegularFile(reviewParentTarget.resolve("bill-java-code-review.md")),
        "platform review baseline must install as a sidecar of $PACK_SIDECAR_PARENT_SKILL",
      )
      assertTrue(
        Files.isRegularFile(reviewParentTarget.resolve("bill-java-code-review-security.md")),
        "platform review specialists must install as sidecars of $PACK_SIDECAR_PARENT_SKILL",
      )
    }
}

private fun scaffoldWithInstall(payload: Map<String, Any?>) {
  val repoValidation = FileSystemScaffoldRepoValidation()
  val sourceLoader = FileSystemScaffoldSourceLoader()
  scaffoldWithAdapters(
    payload,
    dryRun = false,
    ScaffoldAdapterSeams(
      validateScaffold = { plan, repoRoot -> repoValidation.validateScaffold(plan, repoRoot) },
      optionalBaselineLayers = { p, r, np -> repoValidation.optionalBaselineLayers(p, r, np) },
      resolveAddonConsumerSkillDirs = { p, pr, pk -> sourceLoader.resolveAddonConsumerSkillDirs(p, pr, pk) },
      performInstall = { txn, plan, repoRoot -> performScaffoldInstall(txn, plan, repoRoot) },
      rollbackInstallTargets = { txn, errors -> rollbackScaffoldInstallTargets(txn, errors) },
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
