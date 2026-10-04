package skillbill.infrastructure.skills.install

import org.junit.jupiter.api.Assumptions
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.NativeAgentProviderId
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.SupportedAgent
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.ports.repository.toFileLocation
import skillbill.testing.seedConformingPlatformPack
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

open class InstallApplyTestSupport {
  protected val tempDirs = mutableListOf<Path>()

  @AfterTest
  fun cleanup() {
    tempDirs.reversed().forEach { dir ->
      if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
        Files.walk(dir).use { stream ->
          stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
      }
    }
  }

  protected fun setupApplyFixture(): ApplyFixture {
    val repoRoot = Files.createTempDirectory("skillbill-install-apply-repo").also(tempDirs::add)
    val home = Files.createTempDirectory("skillbill-install-apply-home").also(tempDirs::add)
    seedBaseSkill(repoRoot, PACK_SIDECAR_PARENT_SKILL)
    seedBaseSkill(repoRoot, "bill-code-review", nativeAgentName = "bill-code-review-worker")
    seedBaseSkill(repoRoot, "bill-code-check")
    seedBaseSkill(repoRoot, "bill-update-check")
    seedPlatformPack(repoRoot, "kotlin", nativeAgentName = "bill-kotlin-code-review-worker")
    seedPlatformPack(repoRoot, "kmp", nativeAgentName = "bill-kmp-code-review-worker")
    return ApplyFixture(repoRoot, home)
  }

  protected fun snapshotSource(root: Path): Map<String, String> =
    Files.walk(root).use { stream ->
      stream
        .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
        .sorted()
        .toList()
        .associate { path ->
          root.relativize(path).toString().replace(File.separatorChar, '/') to Files.readString(path)
        }
        .filterKeys { relativePath -> !relativePath.startsWith(".skill-bill/") && relativePath != ".gitignore" }
    }

  protected fun assertSourceUnchanged(
    root: Path,
    before: Map<String, String>,
  ) {
    assertEquals(before, snapshotSource(root), "install flow mutated source files")
  }

  protected fun assertStagingUnderHomeCacheAndOutsideSource(
    fixture: ApplyFixture,
    stagingDir: Path?,
    label: String,
  ) {
    val staged = assertNotNull(stagingDir, "$label did not expose a staging directory")
    assertTrue(staged.startsWith(fixture.home.resolve(".skill-bill/installed-skills")), "$label staged outside cache")
    assertFalse(staged.startsWith(fixture.repoRoot), "$label staged inside source")
  }

  protected fun assertNativeProviders(
    actual: Set<NativeAgentProviderId>,
    expected: Set<NativeAgentProviderId>,
  ) {
    assertEquals(expected, actual.filter { provider -> provider in expected }.toSet())
  }

  protected fun createSymlinkOrSkip(
    linkPath: Path,
    target: Path,
  ) {
    val created =
      runCatching {
        Files.createSymbolicLink(linkPath, target)
      }.isSuccess
    Assumptions.assumeTrue(created, "symlinks unsupported on this filesystem")
  }

  protected fun readPosixPermissionsOrSkip(path: Path): Set<PosixFilePermission> {
    val permissions = runCatching { Files.getPosixFilePermissions(path) }.getOrNull()
    Assumptions.assumeTrue(permissions != null, "POSIX permissions unsupported on this filesystem")
    return permissions.orEmpty()
  }

  protected fun readSymlinkTarget(linkPath: Path): Path {
    val target = Files.readSymbolicLink(linkPath)
    val resolved = if (target.isAbsolute) target else linkPath.parent.resolve(target)
    return resolved.toAbsolutePath().normalize()
  }

  protected fun seedBaseSkill(
    repoRoot: Path,
    name: String,
    nativeAgentName: String? = null,
  ) {
    val skillDir = repoRoot.resolve("skills/$name")
    Files.createDirectories(skillDir)
    Files.writeString(skillDir.resolve("content.md"), content(name))
    nativeAgentName?.let { seedNativeAgent(skillDir, it) }
  }

  protected fun seedPlatformPack(
    repoRoot: Path,
    slug: String,
    nativeAgentName: String? = null,
  ) {
    seedConformingPlatformPack(repoRoot, slug)
    nativeAgentName?.let { name ->
      seedNativeAgent(repoRoot.resolve("platform-packs/$slug/code-review/bill-$slug-code-review"), name)
    }
  }

  private fun seedNativeAgent(
    skillDir: Path,
    name: String,
  ) {
    val nativeAgentDir = skillDir.resolve("native-agents")
    Files.createDirectories(nativeAgentDir)
    Files.writeString(
      nativeAgentDir.resolve("$name.md"),
      """
      |---
      |name: $name
      |description: Test native agent.
      |---
      |
      |# $name
      |
      |Do the work.
      |
      """.trimMargin(),
    )
  }

  protected fun content(
    name: String,
    internalFor: String? = null,
  ): String =
    buildString {
      appendLine("---")
      appendLine("name: $name")
      appendLine("description: Test skill.")
      internalFor?.let { parent -> appendLine("internal-for: $parent") }
      appendLine("---")
      appendLine()
      appendLine("Test body.")
    }

  protected companion object {
    val allInstallAgents: Set<SupportedAgent> = SupportedAgent.entries.toSet()
  }
}

data class ApplyFixture(
  val repoRoot: Path,
  val home: Path,
) {
  fun request(
    selectedPlatforms: Set<String> = emptySet(),
    agents: Set<SupportedAgent> = setOf(SupportedAgent.CODEX, SupportedAgent.CLAUDE),
    telemetryLevel: InstallTelemetryLevel = InstallTelemetryLevel.ANONYMOUS,
    mcpRegistrationChoice: McpRegistrationChoice =
      McpRegistrationChoice(
        register = true,
        runtimeMcpBin = home.resolve(".skill-bill/runtime/runtime-mcp/bin/runtime-mcp").toFileLocation(),
      ),
    replaceExistingSkillBillLinks: Boolean = false,
  ): InstallPlanRequest {
    val windowsSymlinkPreflight =
      WindowsSymlinkPreflight(
        state = WindowsSymlinkPreflightState.NOT_WINDOWS,
        decision = WindowsSymlinkDecision.NOT_REQUIRED,
      )
    val platformSelectionMode =
      if (selectedPlatforms.isEmpty()) {
        PlatformPackSelectionMode.NONE
      } else {
        PlatformPackSelectionMode.SELECTED
      }
    val targetPaths =
      InstallationTargetPaths(
        skillsRoot = repoRoot.resolve("skills").toFileLocation(),
        platformPacksRoot = repoRoot.resolve("platform-packs").toFileLocation(),
        agentTargets =
          agents.map { agent ->
            InstallAgentTarget(
              agent = agent,
              path = home.resolve("agent-skill-targets/${agent.id}").toFileLocation(),
              source = InstallAgentTargetSource.MANUAL,
            )
          },
      )
    return InstallPlanRequest(
      repoRoot = repoRoot.toFileLocation(),
      home = home.toFileLocation(),
      agentSelection =
        InstallAgentSelection(
          mode = InstallAgentSelectionMode.MANUAL,
          manualAgents = agents,
        ),
      platformPackSelection =
        PlatformPackSelection(
          mode = platformSelectionMode,
          selectedSlugs = selectedPlatforms,
        ),
      telemetryLevel = telemetryLevel,
      mcpRegistrationChoice = mcpRegistrationChoice,
      runtimeDistributionInputs =
        RuntimeDistributionInputs(
          runtimeInstallRoot = home.resolve(".skill-bill/runtime").toFileLocation(),
        ),
      targetPaths = targetPaths,
      windowsSymlinkPreflight = windowsSymlinkPreflight,
      replaceExistingSkillBillLinks = replaceExistingSkillBillLinks,
      environment = installTestEnvironment(home),
    )
  }

  fun requestWithWindowsSymlinkPreflight(windowsSymlinkPreflight: WindowsSymlinkPreflight): InstallPlanRequest =
    request().copy(windowsSymlinkPreflight = windowsSymlinkPreflight)
}
