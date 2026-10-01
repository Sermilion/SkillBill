package skillbill.infrastructure.skills.install

import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.SupportedAgent
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import skillbill.testing.seedConformingPlatformPack
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallPlanContractCoverageTest {
  private val tempDirs = mutableListOf<Path>()

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

  @Test
  fun `plan output is driven by discovered manifests and stages generated artifacts outside source`() {
    val fixture = setupPlanFixture()
    seedPlatformPack(fixture.repoRoot, "python", areaNames = listOf("security"))
    val before = snapshotTree(fixture.repoRoot)

    val plan =
      planInstallForTest(
        fixture.request(
          platformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.ALL),
        ),
      )

    assertEquals(listOf("kmp", "kotlin", "python"), plan.discoveredPlatformPacks.map { pack -> pack.slug })
    assertEquals(listOf("kmp", "kotlin", "python"), plan.selectedPlatformSlugs)
    val plannedSkills = plan.skills.associateBy { skill -> skill.name }
    assertEquals(InstallPlanSkillKind.BASE, plannedSkills.getValue("bill-code-review").kind)
    assertEquals(InstallPlanSkillKind.BASE, plannedSkills.getValue("bill-code-check").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, plannedSkills.getValue("bill-python-code-review").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, plannedSkills.getValue("bill-python-code-review-security").kind)
    assertFalse(plannedSkills.containsKey("bill-python-code-check"))

    assertEquals(
      plan.skills.filter { skill -> skill.internalFor == null }.map { skill -> skill.name },
      plan.staging.skillPaths.map { path -> path.skillName },
    )
    plan.staging.skillPaths.forEach { intent ->
      assertEquals(plan.staging.root, intent.stagingRoot)
      assertTrue(intent.stagingDir.startsWith(fixture.home.resolve(".skill-bill/installed-skills").toFileLocation()))
      assertFalse(
        intent.stagingDir.startsWith(fixture.repoRoot.toFileLocation()),
        "${intent.skillName} staged inside source",
      )
      assertFalse(
        Files.exists(intent.sourceDir.resolve("SKILL.md").toPath()),
        "${intent.skillName} wrote SKILL.md into source",
      )
    }
    assertEquals(before, snapshotTree(fixture.repoRoot), "planning must not write generated governed artifacts")
  }

  @Test
  fun `manual selected agent plan covers all supported agents and MCP intent`() {
    val fixture = setupPlanFixture()
    val explicitTargets =
      SupportedAgent.entries.map { agent ->
        InstallAgentTarget(
          agent = agent,
          path = fixture.home.resolve("manual-targets/${agent.id}").toFileLocation(),
          source = InstallAgentTargetSource.MANUAL,
        )
      }

    val plan =
      planInstallForTest(
        fixture.request(
          agentSelection =
            InstallAgentSelection(
              mode = InstallAgentSelectionMode.MANUAL,
              manualAgents = SupportedAgent.entries.toSet(),
            ),
          targetPaths = fixture.targetPaths(agentTargets = explicitTargets),
        ),
      )

    val expectedAgents = SupportedAgent.entries.sortedBy(SupportedAgent::id)
    assertEquals(expectedAgents, plan.agents.map { target -> target.agent })
    assertEquals(expectedAgents, plan.mcpRegistrationIntent.agents)
    assertTrue(plan.mcpRegistrationIntent.register)
    assertEquals(fixture.runtimeMcpBin, plan.mcpRegistrationIntent.runtimeMcpBin?.toPath())
    plan.agents.forEach { target ->
      assertEquals(InstallAgentTargetSource.MANUAL, target.source)
      assertEquals(fixture.home.resolve("manual-targets/${target.agent.id}"), target.path.toPath())
    }
  }

  @Test
  fun `detection derived agent selection covers all supported agents without source mutation`() {
    val fixture = setupPlanFixture()
    Files.createDirectories(fixture.home.resolve(".claude"))
    Files.createDirectories(fixture.home.resolve(".codex"))
    Files.createDirectories(fixture.home.resolve(".junie"))
    Files.createDirectories(fixture.home.resolve(".cursor"))
    val before = snapshotTree(fixture.repoRoot)

    val plan =
      planInstallForTest(
        fixture.request(
          agentSelection = InstallAgentSelection(mode = InstallAgentSelectionMode.DETECTED),
        ),
      )

    assertEquals(
      listOf(
        SupportedAgent.CLAUDE,
        SupportedAgent.CODEX,
        SupportedAgent.CODEX,
        SupportedAgent.JUNIE,
        SupportedAgent.CURSOR,
      ),
      plan.agents.map { target -> target.agent },
    )
    assertEquals(
      listOf(
        SupportedAgent.CLAUDE,
        SupportedAgent.CODEX,
        SupportedAgent.CODEX,
        SupportedAgent.JUNIE,
        SupportedAgent.CURSOR,
      ),
      plan.mcpRegistrationIntent.agents,
    )
    assertEquals(
      listOf(
        fixture.home.resolve(".claude/skills"),
        fixture.home.resolve(".codex/skills"),
        fixture.home.resolve(".agents/skills"),
        fixture.home.resolve(".junie/skills"),
        fixture.home.resolve(".cursor/skills"),
      ),
      plan.agents.map { target -> target.path.toPath() },
    )
    assertTrue(plan.agents.all { target -> target.source == InstallAgentTargetSource.DETECTED })
    assertEquals(before, snapshotTree(fixture.repoRoot), "detected planning mutated source files")
  }

  @Test
  fun `telemetry choices are represented as stable plan fields without applying`() {
    InstallTelemetryLevel.entries.forEach { level ->
      val fixture = setupPlanFixture()
      val beforeHome = snapshotTree(fixture.home)

      val plan =
        planInstallForTest(
          fixture.request(telemetryLevel = level),
        )

      assertEquals(level, plan.telemetryLevel)
      assertEquals(level, plan.request.telemetryLevel)
      assertEquals(fixture.runtimeInstallRoot, plan.runtimeDistributionInputs.runtimeInstallRoot.toPath())
      assertEquals(fixture.runtimeMcpBin, plan.mcpRegistrationIntent.runtimeMcpBin?.toPath())
      assertTrue(plan.mcpRegistrationIntent.register)
      assertEquals(beforeHome, snapshotTree(fixture.home), "planning telemetry '$level' mutated home")
    }
  }

  @Test
  fun `windows symlink preflight choices are represented in the plan contract`() {
    windowsSymlinkPreflightCases().forEach { preflight ->
      val fixture = setupPlanFixture()

      val plan =
        planInstallForTest(
          fixture.request(windowsSymlinkPreflight = preflight),
        )

      assertEquals(preflight, plan.windowsSymlinkPreflight)
      assertEquals(preflight, plan.request.windowsSymlinkPreflight)
      assertEquals(fixture.home.resolve(".skill-bill/installed-skills"), plan.staging.root.toPath())
    }
  }

  private fun setupPlanFixture(): PlanFixture {
    val repoRoot = Files.createTempDirectory("skillbill-install-plan-contract-repo").also(tempDirs::add)
    val home = Files.createTempDirectory("skillbill-install-plan-contract-home").also(tempDirs::add)
    seedBaseSkill(repoRoot, PACK_SIDECAR_PARENT_SKILL)
    seedBaseSkill(repoRoot, "bill-code-review")
    seedBaseSkill(repoRoot, "bill-code-check")
    seedPlatformPack(repoRoot, "kotlin", areaNames = listOf("architecture", "testing"))
    seedPlatformPack(repoRoot, "kmp", areaNames = listOf("architecture", "testing"))
    return PlanFixture(repoRoot = repoRoot, home = home)
  }

  private fun seedBaseSkill(
    repoRoot: Path,
    skillName: String,
  ) {
    val skillDir = repoRoot.resolve("skills").resolve(skillName)
    Files.createDirectories(skillDir)
    Files.writeString(skillDir.resolve("content.md"), content(skillName))
  }

  private fun seedPlatformPack(
    repoRoot: Path,
    slug: String,
    areaNames: List<String>,
  ) {
    seedConformingPlatformPack(repoRoot, slug, areaNames)
  }

  private fun content(
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
      appendLine("# $name")
      appendLine()
      appendLine("Test body.")
    }

  private fun snapshotTree(root: Path): Map<String, String> {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return emptyMap()
    }
    return Files.walk(root).use { stream ->
      stream
        .sorted()
        .toList()
        .associate { path ->
          val relative =
            root.relativize(path)
              .toString()
              .replace(File.separatorChar, '/')
              .ifEmpty { "." }
          val value =
            when {
              Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> "<DIR>"
              Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> Files.readString(path)
              else -> "<OTHER>"
            }
          relative to value
        }
    }
  }

  private fun windowsSymlinkPreflightCases(): List<WindowsSymlinkPreflight> =
    listOf(
      WindowsSymlinkPreflight(
        state = WindowsSymlinkPreflightState.NOT_WINDOWS,
        decision = WindowsSymlinkDecision.NOT_REQUIRED,
        message = "",
      ),
      WindowsSymlinkPreflight(
        state = WindowsSymlinkPreflightState.AVAILABLE,
        decision = WindowsSymlinkDecision.PROCEED_WITH_SYMLINKS,
        message = "Windows symlink support is available.",
      ),
      WindowsSymlinkPreflight(
        state = WindowsSymlinkPreflightState.REQUIRES_ELEVATION_OR_DEVELOPER_MODE,
        decision = WindowsSymlinkDecision.PROCEED_WITH_SYMLINKS,
        message = "Windows symlink support was not confirmed.",
      ),
      WindowsSymlinkPreflight(
        state = WindowsSymlinkPreflightState.DECISION_REQUIRED,
        decision = WindowsSymlinkDecision.REQUIRE_USER_ACTION,
        message = "Windows requires elevation or Developer Mode before symlink install.",
      ),
    )

  private data class PlanFixture(
    val repoRoot: Path,
    val home: Path,
  ) {
    val runtimeInstallRoot: Path = home.resolve(".skill-bill/runtime")
    val runtimeMcpBin: Path = runtimeInstallRoot.resolve("runtime-mcp/bin/runtime-mcp")

    fun targetPaths(agentTargets: List<InstallAgentTarget> = emptyList()): InstallationTargetPaths =
      InstallationTargetPaths(
        skillsRoot = repoRoot.resolve("skills").toFileLocation(),
        platformPacksRoot = repoRoot.resolve("platform-packs").toFileLocation(),
        agentTargets = agentTargets,
      )

    fun request(
      agentSelection: InstallAgentSelection =
        InstallAgentSelection(
          mode = InstallAgentSelectionMode.MANUAL,
          manualAgents = setOf(SupportedAgent.CODEX),
        ),
      platformPackSelection: PlatformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.NONE),
      telemetryLevel: InstallTelemetryLevel = InstallTelemetryLevel.ANONYMOUS,
      targetPaths: InstallationTargetPaths = targetPaths(),
      windowsSymlinkPreflight: WindowsSymlinkPreflight =
        WindowsSymlinkPreflight(
          state = WindowsSymlinkPreflightState.NOT_WINDOWS,
          decision = WindowsSymlinkDecision.NOT_REQUIRED,
        ),
    ): InstallPlanRequest =
      InstallPlanRequest(
        repoRoot = repoRoot.toFileLocation(),
        home = home.toFileLocation(),
        agentSelection = agentSelection,
        platformPackSelection = platformPackSelection,
        telemetryLevel = telemetryLevel,
        mcpRegistrationChoice = McpRegistrationChoice(register = true, runtimeMcpBin = runtimeMcpBin.toFileLocation()),
        runtimeDistributionInputs = RuntimeDistributionInputs(runtimeInstallRoot = runtimeInstallRoot.toFileLocation()),
        targetPaths = targetPaths,
        windowsSymlinkPreflight = windowsSymlinkPreflight,
        environment = installTestEnvironment(home),
      )
  }
}
