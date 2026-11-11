package skillbill.infrastructure.skills.install

import org.junit.jupiter.api.Assumptions
import skillbill.infrastructure.skills.externalplatformpack.FileExternalPlatformPackSourceConfigStore
import skillbill.infrastructure.skills.install.apply.currentNativeAgentApplyCacheRoot
import skillbill.infrastructure.skills.install.nativeagent.InstallNativeAgentResult
import skillbill.infrastructure.skills.install.nativeagent.inventory.NativeAgentLinkInventory
import skillbill.infrastructure.skills.install.nativeagent.link.NativeAgentLinkOwnership
import skillbill.infrastructure.skills.install.nativeagent.link.installNativeAgentFile
import skillbill.infrastructure.skills.nativeagent.NativeAgentOperations
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformManifest
import skillbill.install.model.AgentTarget
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.NativeAgentApplyStatus
import skillbill.install.model.SupportedAgent
import skillbill.ports.install.model.NativeAgentLinkOverrides
import skillbill.ports.install.model.NativeAgentLinkProvider
import skillbill.ports.install.model.NativeAgentLinkRequest
import skillbill.ports.install.nativeagent.model.InstallNativeAgentLinkOperationRequest
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InstallNativeAgentLinkApplyCursorTest : InstallNativeAgentLinkApplyTestSupport() {
  @Test
  fun `unlink retains replacement targets and removes owned links from a custom cache`() {
    listOf(NativeAgentLinkProvider.CODEX, NativeAgentLinkProvider.OPENCODE).forEach { provider ->
      val fixture = setupApplyFixture()
      Files.createDirectories(fixture.home.resolve(".codex/agents"))
      val cacheRoot = fixture.home.resolve("custom-native-cache")
      val port =
        FileSystemInstallNativeAgentLinks(PlatformPackCatalogLoader(FileExternalPlatformPackSourceConfigStore()))
      val request =
        InstallNativeAgentLinkOperationRequest(
          provider = provider,
          linkRequest =
            NativeAgentLinkRequest(
              platformPacksRoot = fixture.repoRoot.resolve("platform-packs"),
              skillsRoot = fixture.repoRoot.resolve("skills"),
              home = fixture.home,
              selectedPlatforms = listOf("kotlin"),
              environment = installTestEnvironment(fixture.home),
              overrides = NativeAgentLinkOverrides(installCacheRoot = cacheRoot),
            ),
        )
      val linked = port.linkNativeAgents(request).outcome.linked
      assertTrue(linked.size > 1)
      val replacementLink = linked.first()
      val originalEntries = NativeAgentLinkInventory.read(fixture.home, listOf(cacheRoot), fixture.repoRoot)
      val replacement = fixture.home.resolve(".skill-bill/user-workers/${replacementLink.fileName}")
      Files.createDirectories(replacement.parent)
      Files.writeString(replacement, "user-owned replacement")
      Files.delete(replacementLink)
      createSymlinkOrSkip(replacementLink, replacement)

      val reapplied = port.linkNativeAgents(request)
      assertTrue(reapplied.outcome.skipped.any { it.path == replacementLink })
      assertEquals(
        originalEntries.filter { it.installedPath == replacementLink },
        NativeAgentLinkInventory.read(fixture.home, listOf(cacheRoot), fixture.repoRoot),
      )

      port.linkNativeAgents(request.copy(linkRequest = request.linkRequest.copy(selectedPlatforms = emptyList())))
      assertEquals(
        originalEntries.filter { it.installedPath == replacementLink },
        NativeAgentLinkInventory.read(fixture.home, listOf(cacheRoot), fixture.repoRoot),
      )

      val result = port.unlinkNativeAgents(request)

      assertEquals(listOf(replacement), result.retainedPaths)
      assertEquals(linked.drop(1).toSet(), result.unlinked.toSet())
      result.unlinked.forEach { link -> assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS)) }
      assertEquals(replacement, readSymlinkTarget(replacementLink))
      assertEquals("user-owned replacement", Files.readString(replacementLink))
      assertEquals(
        originalEntries.filter { it.installedPath == replacementLink },
        NativeAgentLinkInventory.read(fixture.home, listOf(cacheRoot), fixture.repoRoot),
      )
    }
  }

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
    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertTrue(result.failures.isEmpty())
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
    assertTrue(
      result.nativeAgents.any { native ->
        native.agent == SupportedAgent.OPENCODE && native.status == NativeAgentApplyStatus.LINKED
      },
    )
  }

  @Test
  fun `opencode apply uses configured agents root and retains existing provider inventory`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex/agents"))
    val codexRequest = fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(SupportedAgent.CODEX))
    assertEquals(InstallApplyStatus.SUCCESS, applyInstallForTest(planInstallForTest(codexRequest)).status)

    val configuredRoot = fixture.home.resolve("custom/opencode")
    val request =
      fixture.request(
        selectedPlatforms = setOf("kotlin"),
        agents = setOf(SupportedAgent.OPENCODE),
      )
    val configuredRequest =
      request.copy(environment = request.environment + ("OPENCODE_CONFIG_DIR" to configuredRoot.toString()))
    val plan = planInstallForTest(configuredRequest)
    assertEquals(InstallApplyStatus.SUCCESS, applyInstallForTest(plan).status)

    val link = configuredRoot.resolve("agents/bill-kotlin-code-review-worker.md")
    val cacheRoot =
      currentNativeAgentApplyCacheRoot(
        fixture.home,
        fixture.repoRoot.resolve("platform-packs"),
        fixture.repoRoot.resolve("skills"),
      )
    val artifact = NativeAgentProvider.OpenCode.cacheArtifactPath(cacheRoot, "bill-kotlin-code-review-worker")
    assertEquals(artifact, readSymlinkTarget(link))
    assertContains(Files.readString(artifact), "mode: primary")
    assertTrue(Files.isRegularFile(cacheRoot.resolve("review-catalog/platform-packs/kotlin/platform.yaml")))
    val installedEntries =
      NativeAgentLinkInventory.read(fixture.home, listOf(cacheRoot), fixture.repoRoot, configuredRequest.environment)
    assertEquals(setOf("codex", "opencode"), installedEntries.map { it.provider }.toSet())

    assertEquals(InstallApplyStatus.SUCCESS, applyInstallForTest(plan).status)
    assertEquals(
      installedEntries,
      NativeAgentLinkInventory.read(fixture.home, listOf(cacheRoot), fixture.repoRoot, configuredRequest.environment),
    )

    Files.delete(link)
    Files.writeString(link, "user replacement")
    assertEquals(InstallApplyStatus.SUCCESS, applyInstallForTest(plan).status)
    assertEquals("user replacement", Files.readString(link))
    assertEquals(
      installedEntries.filterNot { it.installedPath == link },
      NativeAgentLinkInventory.read(fixture.home, listOf(cacheRoot), fixture.repoRoot, configuredRequest.environment),
    )
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
  fun `replacement apply preserves a deselected platform link repointed by its user`() {
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
    assertTrue(Files.isSymbolicLink(kmpNativeAgent))
    assertEquals(legacyKmpNativeAgent.toAbsolutePath().normalize(), readSymlinkTarget(kmpNativeAgent))
    assertEquals("legacy kmp", Files.readString(kmpNativeAgent))
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
  fun `installed review catalog loads declared QA guidance and excludes unrelated files`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val sourcePack = fixture.repoRoot.resolve("platform-packs/kotlin")
    val manifest = sourcePack.resolve("platform.yaml")
    Files.writeString(
      manifest,
      Files.readString(manifest) +
        "\nqa_capability:\n  platforms: [android]\n  guidance_file: qa/android-qa.md\n",
    )
    Files.createDirectories(sourcePack.resolve("qa"))
    Files.writeString(sourcePack.resolve("qa/android-qa.md"), "Exercise the declared Android flows.")
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
    val capability = assertNotNull(loadPlatformManifest(installedPack).qaCapability)
    assertEquals("qa/android-qa.md", capability.guidanceFile)
    assertEquals(
      "Exercise the declared Android flows.",
      Files.readString(installedPack.resolve(capability.guidanceFile)),
    )
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
    val oldSource = NativeAgentProvider.Junie.cacheArtifactPath(managedRoot, "bill-worker")
    Files.createDirectories(oldSource.parent)
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
        runCatching {
          installNativeAgentFile(
            source = newSource,
            agentTarget = AgentTarget("junie", targetDir.toFileLocation()),
            managedSourceRoots = listOf(managedRoot),
            ownership = NativeAgentLinkOwnership(managedRoot, NativeAgentProvider.Junie, "bill-worker", oldSource),
          )
        }.exceptionOrNull()

      assertNotNull(failure, "replacement should fail in read-only target dir")
      assertEquals(oldSource.toAbsolutePath().normalize(), readSymlinkTarget(linkPath))
    } finally {
      Files.setPosixFilePermissions(targetDir, originalPermissions)
    }
  }
}
