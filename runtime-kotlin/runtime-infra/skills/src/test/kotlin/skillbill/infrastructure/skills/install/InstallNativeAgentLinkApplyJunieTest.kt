package skillbill.infrastructure.skills.install

import skillbill.error.core.failureCodeLabel
import skillbill.infrastructure.skills.install.apply.InstallApplyFailureCode
import skillbill.infrastructure.skills.install.apply.createNewSymlinkWithGuidance
import skillbill.infrastructure.skills.install.apply.currentNativeAgentApplyCacheRoot
import skillbill.infrastructure.skills.install.apply.failedNativeAgentOutcome
import skillbill.infrastructure.skills.install.apply.windowsSymlinkGuidance
import skillbill.infrastructure.skills.install.nativeagent.InstallNativeAgentResult
import skillbill.infrastructure.skills.install.nativeagent.inventory.NativeAgentLinkInventory
import skillbill.infrastructure.skills.install.nativeagent.link.NativeAgentLinkFailure
import skillbill.infrastructure.skills.install.nativeagent.link.NativeAgentLinkOutcome
import skillbill.infrastructure.skills.install.nativeagent.link.NativeAgentLinkOwnership
import skillbill.infrastructure.skills.install.nativeagent.link.ProviderMutationJournal
import skillbill.infrastructure.skills.install.nativeagent.link.installNativeAgentFile
import skillbill.infrastructure.skills.install.nativeagent.link.linkProviderAgentsWithJournal
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.install.model.AgentTarget
import skillbill.install.model.InstallAgentLinkStatus
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.McpRegistrationApplyStatus
import skillbill.install.model.NativeAgentApplyStatus
import skillbill.install.model.NativeAgentProviderId
import skillbill.install.model.SupportedAgent
import skillbill.ports.repository.toFileLocation
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InstallNativeAgentLinkApplyJunieTest : InstallNativeAgentLinkApplyTestSupport() {
  @Test
  fun `apply removes inventory-recorded dangling baseline orchestrator links`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val agentDir = fixture.home.resolve(".codex/agents")
    Files.createDirectories(agentDir)
    val managedRoot =
      fixture.home.resolve(
        ".skill-bill/installed-skills/native-agents-skill-bill-0123456789abcdef/codex-agents",
      )
    Files.createDirectories(managedRoot)
    val kotlinTarget = managedRoot.resolve("bill-kotlin-code-review.toml")
    val kmpTarget = managedRoot.resolve("bill-kmp-code-review.toml")
    val kotlinLink = agentDir.resolve(kotlinTarget.fileName)
    val kmpLink = agentDir.resolve(kmpTarget.fileName)
    createSymlinkOrSkip(kotlinLink, kotlinTarget)
    createSymlinkOrSkip(kmpLink, kmpTarget)
    val inventory = fixture.home.resolve(".skill-bill/native-agent-link-inventory.json")
    Files.createDirectories(inventory.parent)
    Files.writeString(
      inventory,
      """
      {"contract_version":"0.2","entries":[
        {"logical_name":"bill-kotlin-code-review","provider":"codex","installed_path":"$kotlinLink",
          "cache_target_path":"$kotlinTarget","content_digest":"${"0".repeat(
        64,
      )}","source_root":"${fixture.repoRoot}"},
        {"logical_name":"bill-kmp-code-review","provider":"codex","installed_path":"$kmpLink",
          "cache_target_path":"$kmpTarget","content_digest":"${"0".repeat(
        64,
      )}","source_root":"${fixture.repoRoot}"}
      ]}
      """.trimIndent(),
    )
    val plan =
      planInstallForTest(
        fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(SupportedAgent.CODEX)),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertFalse(Files.exists(kotlinLink, LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(kmpLink, LinkOption.NOFOLLOW_LINKS))
  }

  @Test
  fun `missing inventory removes canonical dangling links across provider layouts`() {
    val fixture = setupApplyFixture()
    listOf(".claude", ".codex", ".junie", ".cursor")
      .forEach { Files.createDirectories(fixture.home.resolve(it)) }
    val cacheRoot =
      fixture.home.resolve(
        ".skill-bill/installed-skills/native-agents-moved-checkout-0123456789abcdef",
      )
    val danglingLinks =
      NativeAgentProvider.entries.map { provider ->
        val agentDir = provider.homeAgentDirs(fixture.home).first()
        Files.createDirectories(agentDir)
        val logicalName = "bill-obsolete-${provider.name.lowercase()}-worker"
        val target = provider.cacheArtifactPath(cacheRoot, logicalName)
        agentDir.resolve(provider.fileName(logicalName)).also { createSymlinkOrSkip(it, target) }
      }
    Files.deleteIfExists(fixture.home.resolve(".skill-bill/native-agent-link-inventory.json"))
    val plan =
      planInstallForTest(
        fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(SupportedAgent.CODEX)),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    danglingLinks.forEach { link ->
      assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS), "canonical dangling link survived: $link")
    }
  }

  @Test
  fun `missing inventory preserves noncanonical installed-skills provider links`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val agentDir = fixture.home.resolve(".codex/agents")
    Files.createDirectories(agentDir)
    val target =
      fixture.home.resolve(
        ".skill-bill/installed-skills/codex-agents/bill-user-managed-worker.toml",
      )
    val link = agentDir.resolve(target.fileName)
    createSymlinkOrSkip(link, target)
    val plan =
      planInstallForTest(
        fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(SupportedAgent.CODEX)),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertTrue(Files.isSymbolicLink(link))
    assertEquals(target.toAbsolutePath().normalize(), readSymlinkTarget(link))
  }

  @Test
  fun `apply replaces desired links from obsolete canonical generations before verification`() {
    listOf(false, true).forEach { inventoryExists ->
      listOf(false, true).forEach { dangling ->
        val fixture = setupApplyFixture()
        Files.createDirectories(fixture.home.resolve(".codex"))
        val logicalName = "bill-code-review-worker"
        val agentDir = fixture.home.resolve(".codex/agents")
        Files.createDirectories(agentDir)
        val obsoleteRoot =
          fixture.home.resolve(
            ".skill-bill/installed-skills/native-agents-old-checkout-0123456789abcdef",
          )
        val obsoleteTarget = NativeAgentProvider.Codex.cacheArtifactPath(obsoleteRoot, logicalName)
        if (!dangling) {
          Files.createDirectories(obsoleteTarget.parent)
          Files.writeString(obsoleteTarget, "name = \"$logicalName\"\n")
        }
        val installed = agentDir.resolve(NativeAgentProvider.Codex.fileName(logicalName))
        createSymlinkOrSkip(installed, obsoleteTarget)
        if (inventoryExists) {
          val inventory = fixture.home.resolve(".skill-bill/native-agent-link-inventory.json")
          Files.createDirectories(inventory.parent)
          Files.writeString(inventory, inventoryJson(logicalName, installed, obsoleteTarget, fixture.repoRoot))
        }
        val plan =
          planInstallForTest(
            fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(SupportedAgent.CODEX)),
          )

        val result = applyInstallForTest(plan)

        assertEquals(InstallApplyStatus.SUCCESS, result.status)
        val currentRoot =
          currentNativeAgentApplyCacheRoot(
            fixture.home,
            fixture.repoRoot.resolve("platform-packs"),
            fixture.repoRoot.resolve("skills"),
          )
        assertEquals(
          NativeAgentProvider.Codex.cacheArtifactPath(currentRoot, logicalName),
          readSymlinkTarget(installed),
          "inventory=$inventoryExists dangling=$dangling",
        )
        val entries = NativeAgentLinkInventory.read(fixture.home, listOf(currentRoot), fixture.repoRoot)
        assertTrue(entries.single { it.installedPath == installed }.contentDigest != "0".repeat(64))
      }
    }
  }

  @Test
  fun `selected all-agent apply links skills and native agents for every supported agent`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".claude"))
    Files.createDirectories(fixture.home.resolve(".codex"))
    Files.createDirectories(fixture.home.resolve(".junie"))
    Files.createDirectories(fixture.home.resolve(".cursor"))
    val sourceBefore = snapshotSource(fixture.repoRoot)
    val plan =
      planInstallForTest(
        fixture.request(
          selectedPlatforms = setOf("kotlin"),
          agents = allInstallAgents,
        ),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertEquals(SupportedAgent.entries.sortedBy(SupportedAgent::id), result.mcpRegistrationIntent.agents)
    assertEquals(
      SupportedAgent.entries.toSet(),
      result.mcpRegistrationOutcomes.map {
          outcome ->
        outcome.agent
      }.toSet(),
    )
    assertTrue(result.mcpRegistrationOutcomes.all { outcome -> outcome.status == McpRegistrationApplyStatus.SUCCESS })
    result.skills.forEach { skill ->
      assertEquals(
        SupportedAgent.entries.toSet(),
        skill.links.map { link -> link.agent }.toSet(),
        "${skill.skillName} did not link to every selected skill target",
      )
      assertTrue(skill.links.all { link -> link.status == InstallAgentLinkStatus.CREATED })
      assertTrue(
        Files.isSymbolicLink(
          fixture.home.resolve("agent-skill-targets/claude/${skill.skillName}"),
        ),
        "Claude should receive the skill link surface",
      )
    }
    assertEquals(
      setOf(
        NativeAgentProviderId.CLAUDE,
        NativeAgentProviderId.CODEX,
        NativeAgentProviderId.CURSOR,
        NativeAgentProviderId.JUNIE,
      ),
      result.nativeAgents
        .filter { native -> native.status == NativeAgentApplyStatus.LINKED }
        .map { native -> native.provider }
        .toSet(),
    )
    assertSourceUnchanged(fixture.repoRoot, sourceBefore)
  }

  @Test
  fun `new symlink creation preserves destination that appears before move`() {
    val targetDir = Files.createTempDirectory("skillbill-new-link-target").also(tempDirs::add)
    val source = Files.createTempFile("skillbill-new-link-source", ".md").also(tempDirs::add)
    val linkPath = targetDir.resolve("bill-worker.md")
    Files.writeString(linkPath, "user owned")

    val failure = runCatching { createNewSymlinkWithGuidance(linkPath, source) }.exceptionOrNull()

    assertNotNull(failure, "new link creation should fail when destination exists")
    assertEquals("user owned", Files.readString(linkPath))
    assertFalse(Files.isSymbolicLink(linkPath), "user-owned file should not be replaced")
  }

  @Test
  fun `native agent symlink failure returns the failed temporary path and coded guidance`() {
    val targetDir = Files.createTempDirectory("skillbill-native-failure-target").also(tempDirs::add)
    val source = Files.createTempFile("skillbill-native-failure-source", ".md").also(tempDirs::add)
    Files.writeString(source, "source")
    var mutationCount = 0

    val journal = ProviderMutationJournal()
    val result =
      linkProviderAgentsWithJournal(journal) {
        val installed =
          installNativeAgentFile(
            source = source,
            agentTarget = AgentTarget("codex", targetDir.toFileLocation()),
            managedSourceRoots = emptyList(),
            beforeMutation = { path ->
              journal.beforeMutation(path)
              mutationCount += 1
              if (mutationCount == 2) targetDir.toFile().deleteRecursively()
            },
          )
        assertTrue(installed is InstallNativeAgentResult.Failed)
        val failure = installed
        NativeAgentLinkOutcome(
          linked = emptyList(),
          skipped = emptyList(),
          failure = NativeAgentLinkFailure(failure.failedPath, failure.error),
        )
      }

    assertTrue(Files.isDirectory(targetDir), "the provider journal restores the directory removed before linking")
    assertTrue(Files.notExists(targetDir.resolve(source.fileName)))
    val failure = result.failure
    assertNotNull(failure)
    assertEquals(targetDir, failure.path.parent)
    assertTrue(failure.path.fileName.toString().startsWith(".${source.fileName}.tmp-"))
    val error = failure.error
    assertEquals(InstallApplyFailureCode.SYMLINK, error.code)
    assertTrue(error.message.orEmpty().contains("On Windows, enable Developer Mode"))
    val outcome = failedNativeAgentOutcome(SupportedAgent.CODEX, NativeAgentProviderId.CODEX, error, failure.path)
    val issue = assertNotNull(outcome.issue)
    assertEquals(NativeAgentApplyStatus.FAILED, outcome.status)
    assertEquals(InstallApplyIssueKind.NATIVE_AGENT_LINK_FAILED, issue.kind)
    assertEquals(error.message, issue.message)
    assertEquals(failure.path.toFileLocation(), issue.path)
    assertEquals(windowsSymlinkGuidance(), issue.guidance)
    assertEquals(error.failureCodeLabel(), issue.causeClass)
    assertEquals(failure.path.toFileLocation(), outcome.path)
  }

  @Test
  fun `native agent install preserves unmanaged legacy symlink outside current roots`() {
    val targetDir = Files.createTempDirectory("skillbill-native-target").also(tempDirs::add)
    val managedRoot = Files.createTempDirectory("skillbill-native-managed-root").also(tempDirs::add)
    val otherRepo = Files.createTempDirectory("skillbill-native-other-repo").also(tempDirs::add)
    val newSource = managedRoot.resolve("bill-worker.md")
    val userSource = otherRepo.resolve("skills/codex/bill-worker.md")
    Files.createDirectories(userSource.parent)
    Files.writeString(newSource, "new")
    Files.writeString(userSource, "user")
    val linkPath = targetDir.resolve("bill-worker.md")
    createSymlinkOrSkip(linkPath, userSource)

    val result =
      installNativeAgentFile(
        source = newSource,
        agentTarget = AgentTarget("codex", targetDir.toFileLocation()),
        managedSourceRoots = listOf(managedRoot),
      )

    assertTrue(result is InstallNativeAgentResult.Skipped)
    assertEquals(userSource.toAbsolutePath().normalize(), readSymlinkTarget(linkPath))
  }

  @Test
  fun `native agent install replaces a dangling link carried over from another machine`() {
    val targetDir = Files.createTempDirectory("skillbill-native-target").also(tempDirs::add)
    val managedRoot = Files.createTempDirectory("skillbill-native-managed-root").also(tempDirs::add)
    val foreignHome = Files.createTempDirectory("skillbill-native-foreign-home").also(tempDirs::add)
    val newSource = managedRoot.resolve("bill-worker.md")
    Files.writeString(newSource, "new")
    val absentTarget =
      foreignHome.resolve(
        ".skill-bill/installed-skills/native-agents-other-0123456789abcdef/claude-agents/bill-worker.md",
      )
    val linkPath = targetDir.resolve("bill-worker.md")
    createSymlinkOrSkip(linkPath, absentTarget)

    val result =
      installNativeAgentFile(
        source = newSource,
        agentTarget = AgentTarget("claude", targetDir.toFileLocation()),
        managedSourceRoots = listOf(managedRoot),
      )

    assertTrue(result is InstallNativeAgentResult.Linked)
    assertEquals(newSource.toAbsolutePath().normalize(), readSymlinkTarget(linkPath))
  }

  @Test
  fun `native agent install preserves external symlink under similarly named provider directory`() {
    val targetDir = Files.createTempDirectory("skillbill-native-target").also(tempDirs::add)
    val managedRoot = Files.createTempDirectory("skillbill-native-managed-root").also(tempDirs::add)
    val externalRoot = Files.createTempDirectory("skillbill-native-external").also(tempDirs::add)
    val newSource = managedRoot.resolve("codex-agents/bill-worker.toml")
    val userSource = externalRoot.resolve("codex-agents/bill-worker.toml")
    Files.createDirectories(newSource.parent)
    Files.createDirectories(userSource.parent)
    Files.writeString(newSource, "new")
    Files.writeString(userSource, "user")
    val linkPath = targetDir.resolve("bill-worker.toml")
    createSymlinkOrSkip(linkPath, userSource)

    val result =
      installNativeAgentFile(newSource, AgentTarget("codex", targetDir.toFileLocation()), listOf(managedRoot))

    assertTrue(result is InstallNativeAgentResult.Skipped)
    assertEquals(userSource.toAbsolutePath().normalize(), readSymlinkTarget(linkPath))
  }

  @Test
  fun `link decision replaces exact obsolete generations for every provider`() {
    val home = Files.createTempDirectory("skillbill-native-home").also(tempDirs::add)
    NativeAgentProvider.entries.forEach { provider ->
      listOf(
        home.resolve(".skill-bill/installed-skills/native-agents-old-checkout-0123456789abcdef"),
        home.resolve(".skill-bill/native-agents/old-checkout-0123456789abcdef"),
      ).forEachIndexed { index, obsoleteRoot ->
        val logicalName = "bill-worker-$index"
        val currentRoot = home.resolve("current-${provider.name.lowercase()}-$index")
        val currentSource = provider.cacheArtifactPath(currentRoot, logicalName)
        Files.createDirectories(currentSource.parent)
        Files.writeString(currentSource, "current")
        val targetDir = home.resolve("targets/${provider.name.lowercase()}-$index")
        Files.createDirectories(targetDir)
        val installed = targetDir.resolve(provider.fileName(logicalName))
        createSymlinkOrSkip(installed, provider.cacheArtifactPath(obsoleteRoot, logicalName))

        val result =
          installNativeAgentFile(
            source = currentSource,
            agentTarget = AgentTarget(provider.name, targetDir.toFileLocation()),
            managedSourceRoots = listOf(currentRoot),
            ownership = NativeAgentLinkOwnership(home, provider, logicalName),
          )

        assertTrue(result is InstallNativeAgentResult.Linked)
        assertEquals(currentSource.toAbsolutePath().normalize(), readSymlinkTarget(installed))
      }
    }
  }
}
