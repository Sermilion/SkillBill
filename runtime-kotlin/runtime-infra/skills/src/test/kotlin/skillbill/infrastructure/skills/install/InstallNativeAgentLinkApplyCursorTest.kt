package skillbill.infrastructure.skills.install

import org.junit.jupiter.api.Assumptions
import skillbill.infrastructure.skills.install.apply.currentNativeAgentApplyCacheRoot
import skillbill.infrastructure.skills.install.nativeagent.InstallNativeAgentResult
import skillbill.infrastructure.skills.install.nativeagent.inventory.NativeAgentLinkInventory
import skillbill.infrastructure.skills.install.nativeagent.link.installNativeAgentFile
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentOperations
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.install.model.AgentTarget
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.SupportedAgent
import skillbill.ports.repository.toFileLocation
import skillbill.testing.HARBOR_ARCHITECTURE_WORKER
import skillbill.testing.HARBOR_COMPANION_NAME
import skillbill.testing.HARBOR_ENTRYPOINT_MARKER
import skillbill.testing.HARBOR_ENTRYPOINT_NAME
import skillbill.testing.HARBOR_PACK_SLUG
import skillbill.testing.seedHarborAddonPack
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InstallNativeAgentLinkApplyCursorTest : InstallNativeAgentLinkApplyTestSupport() {
  @Test
  fun `inventory deletion followed by multi provider apply publishes canonical readable entries`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    Files.createDirectories(fixture.home.resolve(".cursor"))
    val request =
      fixture.request(
        selectedPlatforms = setOf("kotlin"),
        agents = setOf(SupportedAgent.CODEX, SupportedAgent.CURSOR),
      )
    val first = applyInstallForTest(planInstallForTest(request))
    assertEquals(InstallApplyStatus.SUCCESS, first.status)
    Files.delete(fixture.home.resolve(".skill-bill/native-agent-link-inventory.json"))

    val second = applyInstallForTest(planInstallForTest(request))
    assertEquals(InstallApplyStatus.SUCCESS, second.status)
    val cacheRoot =
      currentNativeAgentApplyCacheRoot(
        fixture.home,
        fixture.repoRoot.resolve("platform-packs"),
        fixture.repoRoot.resolve("skills"),
      )
    val entries = NativeAgentLinkInventory.read(fixture.home, listOf(cacheRoot), fixture.repoRoot)
    assertEquals(setOf("codex", "cursor"), entries.map { it.provider }.toSet())
    assertTrue(entries.all { it.contentDigest != "0".repeat(64) && Files.isReadable(it.cacheTargetPath) })
  }

  @Test
  fun `apply composes declared add-on content into every provider cache artifact`() {
    val fixture = setupApplyFixture()
    seedHarborAddonPack(fixture.repoRoot)
    Files.createDirectories(fixture.home.resolve(".claude"))
    Files.createDirectories(fixture.home.resolve(".codex"))
    Files.createDirectories(fixture.home.resolve(".junie"))
    Files.createDirectories(fixture.home.resolve(".cursor"))
    val result =
      applyInstallForTest(
        planInstallForTest(
          fixture.request(
            selectedPlatforms = setOf(HARBOR_PACK_SLUG),
            agents = allInstallAgents,
          ),
        ),
      )
    assertEquals(InstallApplyStatus.SUCCESS, result.status, "apply failures: ${result.failures}")
    val cacheRoot =
      currentNativeAgentApplyCacheRoot(
        fixture.home,
        fixture.repoRoot.resolve("platform-packs"),
        fixture.repoRoot.resolve("skills"),
      )
    NativeAgentProvider.entries.forEach { provider ->
      val artifact = provider.cacheArtifactPath(cacheRoot, HARBOR_ARCHITECTURE_WORKER)
      assertTrue(Files.isRegularFile(artifact), "${provider.directoryName} missing $HARBOR_ARCHITECTURE_WORKER")
      assertContains(Files.readString(artifact), HARBOR_ENTRYPOINT_MARKER)
    }
  }

  @Test
  fun `native agent install preserves non agent symlink inside source roots`() {
    val targetDir = Files.createTempDirectory("skillbill-native-target").also(tempDirs::add)
    val cacheRoot = Files.createTempDirectory("skillbill-native-cache-root").also(tempDirs::add)
    val repoRoot = Files.createTempDirectory("skillbill-native-repo-root").also(tempDirs::add)
    val newSource = cacheRoot.resolve("bill-worker.md")
    val userSource = repoRoot.resolve("platform-packs/kotlin/README.md")
    Files.createDirectories(userSource.parent)
    Files.writeString(newSource, "new")
    Files.writeString(userSource, "user")
    val linkPath = targetDir.resolve("bill-worker.md")
    createSymlinkOrSkip(linkPath, userSource)

    val result =
      installNativeAgentFile(
        source = newSource,
        agentTarget = AgentTarget("codex", targetDir.toFileLocation()),
        managedSourceRoots = listOf(cacheRoot),
      )

    assertTrue(result is InstallNativeAgentResult.Skipped)
    assertEquals(userSource.toAbsolutePath().normalize(), readSymlinkTarget(linkPath))
  }

  @Test
  fun `apply replaces existing native agent links from legacy generated cache`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val targetDir = fixture.home.resolve(".codex/agents")
    Files.createDirectories(targetDir)
    val legacyRoot =
      NativeAgentOperations.installCacheRoot(
        home = fixture.home,
        platformPacksRoot = fixture.repoRoot.resolve("platform-packs"),
        skillsRoot = fixture.repoRoot.resolve("skills"),
      )
    val legacyFile =
      legacyRoot
        .resolve(NativeAgentProvider.Codex.directoryName)
        .resolve("bill-code-review-worker.${NativeAgentProvider.Codex.extension}")
    Files.createDirectories(legacyFile.parent)
    Files.writeString(legacyFile, "legacy")
    val linkPath = targetDir.resolve(legacyFile.fileName)
    createSymlinkOrSkip(linkPath, legacyFile)
    val plan =
      planInstallForTest(
        fixture.request(
          selectedPlatforms = setOf("kotlin"),
          agents = setOf(SupportedAgent.CODEX),
        ),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val newTarget = readSymlinkTarget(linkPath)
    val installedSkills = fixture.home.toAbsolutePath().normalize().resolve(".skill-bill/installed-skills")
    assertTrue(newTarget.startsWith(installedSkills), "newTarget=$newTarget installedSkills=$installedSkills")
    assertFalse(newTarget.startsWith(legacyRoot.toAbsolutePath().normalize()))
  }

  @Test
  fun `replacement apply removes native agent links from deselected platforms`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val kmpPlan =
      planInstallForTest(
        fixture.request(
          selectedPlatforms = setOf("kmp"),
          agents = setOf(SupportedAgent.CODEX),
        ),
      )
    val first = applyInstallForTest(kmpPlan)
    assertEquals(InstallApplyStatus.SUCCESS, first.status)
    val targetDir = fixture.home.resolve(".codex/agents")
    val baseNativeAgent = targetDir.resolve("bill-code-review-worker.toml")
    val kmpNativeAgent = targetDir.resolve("bill-kmp-code-review-worker.toml")
    assertTrue(Files.isSymbolicLink(baseNativeAgent))
    assertTrue(Files.isSymbolicLink(kmpNativeAgent))
    val legacyRoot =
      NativeAgentOperations.installCacheRoot(
        home = fixture.home,
        platformPacksRoot = fixture.repoRoot.resolve("platform-packs"),
        skillsRoot = fixture.repoRoot.resolve("skills"),
      )
    val legacyKmpNativeAgent =
      legacyRoot
        .resolve(NativeAgentProvider.Codex.directoryName)
        .resolve(kmpNativeAgent.fileName)
    Files.createDirectories(legacyKmpNativeAgent.parent)
    Files.writeString(legacyKmpNativeAgent, "legacy kmp")
    Files.delete(kmpNativeAgent)
    createSymlinkOrSkip(kmpNativeAgent, legacyKmpNativeAgent)
    assertEquals(legacyKmpNativeAgent.toAbsolutePath().normalize(), readSymlinkTarget(kmpNativeAgent))

    val baseOnlyReplacementPlan =
      planInstallForTest(
        fixture.request(
          agents = setOf(SupportedAgent.CODEX),
          replaceExistingSkillBillLinks = true,
        ),
      )
    val second = applyInstallForTest(baseOnlyReplacementPlan)

    assertEquals(InstallApplyStatus.SUCCESS, second.status)
    assertTrue(Files.isSymbolicLink(baseNativeAgent))
    assertFalse(Files.exists(kmpNativeAgent, LinkOption.NOFOLLOW_LINKS))
  }

  @Test
  fun `replacement apply prunes deselected packs from installed review catalog`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val firstPlan =
      planInstallForTest(
        fixture.request(selectedPlatforms = setOf("kmp"), agents = setOf(SupportedAgent.CODEX)),
      )
    assertEquals(InstallApplyStatus.SUCCESS, applyInstallForTest(firstPlan).status)
    val cacheRoot =
      currentNativeAgentApplyCacheRoot(
        fixture.home,
        fixture.repoRoot.resolve("platform-packs"),
        fixture.repoRoot.resolve("skills"),
      )
    val catalog = cacheRoot.resolve("review-catalog/platform-packs")
    assertTrue(Files.isDirectory(catalog.resolve("kmp")))

    val replacementPlan =
      planInstallForTest(
        fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(SupportedAgent.CODEX)),
      )
    assertEquals(InstallApplyStatus.SUCCESS, applyInstallForTest(replacementPlan).status)

    assertFalse(Files.exists(catalog.resolve("kmp"), LinkOption.NOFOLLOW_LINKS))
    assertTrue(Files.isDirectory(catalog.resolve("kotlin")))
  }

  @Test
  fun `failed replacement apply restores the previously published review catalog`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val firstPlan =
      planInstallForTest(
        fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(SupportedAgent.CODEX)),
      )
    assertEquals(InstallApplyStatus.SUCCESS, applyInstallForTest(firstPlan).status)
    val catalog =
      currentNativeAgentApplyCacheRoot(
        fixture.home,
        fixture.repoRoot.resolve("platform-packs"),
        fixture.repoRoot.resolve("skills"),
      ).resolve("review-catalog/platform-packs")
    val publishedManifest = Files.readString(catalog.resolve("kotlin/platform.yaml"))
    val userFile = fixture.home.resolve(".codex/agents/user-notes.md")
    Files.createDirectories(userFile.parent)
    Files.writeString(userFile, "KEEP_USER_BYTES")

    val inventory = fixture.home.resolve(".skill-bill/native-agent-link-inventory.json")
    Files.writeString(inventory, "not-json")
    val replacementPlan =
      planInstallForTest(
        fixture.request(selectedPlatforms = setOf("kmp"), agents = setOf(SupportedAgent.CODEX)),
      )

    val result = applyInstallForTest(replacementPlan)

    assertEquals(InstallApplyStatus.FAILURE, result.status)
    assertEquals(publishedManifest, Files.readString(catalog.resolve("kotlin/platform.yaml")))
    assertFalse(Files.exists(catalog.resolve("kmp"), LinkOption.NOFOLLOW_LINKS))
    assertEquals("KEEP_USER_BYTES", Files.readString(userFile))
  }

  @Test
  fun `installed review catalog contains only manifest and declared review content`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val sourcePack = fixture.repoRoot.resolve("platform-packs/kotlin")
    Files.createDirectories(sourcePack.resolve("agent"))
    Files.writeString(sourcePack.resolve("agent/history.md"), "boundary history")
    Files.writeString(sourcePack.resolve("unrelated-custom-file.txt"), "not review runtime content")
    val plan =
      planInstallForTest(
        fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(SupportedAgent.CODEX)),
      )

    assertEquals(InstallApplyStatus.SUCCESS, applyInstallForTest(plan).status)

    val cacheRoot =
      currentNativeAgentApplyCacheRoot(
        fixture.home,
        fixture.repoRoot.resolve("platform-packs"),
        fixture.repoRoot.resolve("skills"),
      )
    val installedPack = cacheRoot.resolve("review-catalog/platform-packs/kotlin")
    assertTrue(Files.isRegularFile(installedPack.resolve("platform.yaml")))
    assertTrue(
      Files.isRegularFile(installedPack.resolve("code-review/bill-kotlin-code-review/content.md")),
    )
    assertFalse(Files.exists(installedPack.resolve("agent/history.md"), LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(installedPack.resolve("unrelated-custom-file.txt"), LinkOption.NOFOLLOW_LINKS))
  }

  @Test
  fun `installed review catalog includes addon_usage entrypoint and companions`() {
    val fixture = setupApplyFixture()
    seedHarborAddonPack(fixture.repoRoot)
    Files.createDirectories(fixture.home.resolve(".codex"))
    val plan =
      planInstallForTest(
        fixture.request(selectedPlatforms = setOf(HARBOR_PACK_SLUG), agents = setOf(SupportedAgent.CODEX)),
      )

    assertEquals(InstallApplyStatus.SUCCESS, applyInstallForTest(plan).status)

    val installedPack =
      currentNativeAgentApplyCacheRoot(
        fixture.home,
        fixture.repoRoot.resolve("platform-packs"),
        fixture.repoRoot.resolve("skills"),
      ).resolve("review-catalog/platform-packs/$HARBOR_PACK_SLUG")
    assertTrue(Files.isRegularFile(installedPack.resolve("addons/$HARBOR_ENTRYPOINT_NAME")))
    assertTrue(Files.isRegularFile(installedPack.resolve("addons/$HARBOR_COMPANION_NAME")))
    assertEquals(
      "$HARBOR_ENTRYPOINT_MARKER\n",
      Files.readString(installedPack.resolve("addons/$HARBOR_ENTRYPOINT_NAME")),
    )
  }

  @Test
  fun `native agent replacement preserves existing link when replacement symlink creation fails`() {
    val targetDir = Files.createTempDirectory("skillbill-native-readonly-target").also(tempDirs::add)
    val managedRoot = Files.createTempDirectory("skillbill-native-managed-root").also(tempDirs::add)
    val newRoot = Files.createTempDirectory("skillbill-native-new-root").also(tempDirs::add)
    val oldSource = managedRoot.resolve("bill-worker.md")
    val newSource = newRoot.resolve("bill-worker.md")
    Files.writeString(oldSource, "old")
    Files.writeString(newSource, "new")
    val linkPath = targetDir.resolve("bill-worker.md")
    createSymlinkOrSkip(linkPath, oldSource)
    val originalPermissions = readPosixPermissionsOrSkip(targetDir)
    try {
      Files.setPosixFilePermissions(
        targetDir,
        originalPermissions - PosixFilePermission.OWNER_WRITE -
          PosixFilePermission.GROUP_WRITE -
          PosixFilePermission.OTHERS_WRITE,
      )
      val probe = targetDir.resolve("probe")
      val canStillCreateSymlink =
        runCatching {
          Files.createSymbolicLink(probe, newSource)
          Files.deleteIfExists(probe)
        }.isSuccess
      Assumptions.assumeFalse(canStillCreateSymlink, "read-only directory still allows symlink creation")

      val failure =
        installNativeAgentFile(
          source = newSource,
          agentTarget = AgentTarget("codex", targetDir.toFileLocation()),
          managedSourceRoots = listOf(managedRoot),
        )

      assertIs<InstallNativeAgentResult.Failed>(failure, "replacement should fail in read-only target dir")
      assertEquals(oldSource.toAbsolutePath().normalize(), readSymlinkTarget(linkPath))
    } finally {
      Files.setPosixFilePermissions(targetDir, originalPermissions)
    }
  }
}
