package skillbill.infrastructure.skills.install

import skillbill.infrastructure.contracts.install.InstallPlanSchemaValidator
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallPlanRequest
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
import skillbill.install.model.buildInstallPlanWireMap
import skillbill.ports.repository.toFileLocation
import skillbill.testing.seedConformingPlatformPack
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test

class InstallPlanSchemaValidatesExistingFixturesTest {
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
  fun `all platforms selected plan validates against the canonical schema`() {
    val fixture = setupFixture()
    seedPlatformPack(fixture.repoRoot, "python", areaNames = listOf("security"))
    val plan =
      planInstallForTest(
        fixture.request(platformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.ALL)),
      )
    InstallPlanSchemaValidator.validate(buildInstallPlanWireMap(plan))
  }

  @Test
  fun `manual selected agent plan validates against the canonical schema`() {
    val fixture = setupFixture()
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
    InstallPlanSchemaValidator.validate(buildInstallPlanWireMap(plan))
  }

  @Test
  fun `detection derived plan validates against the canonical schema`() {
    val fixture = setupFixture()
    Files.createDirectories(fixture.home.resolve(".claude"))
    Files.createDirectories(fixture.home.resolve(".codex"))
    Files.createDirectories(fixture.home.resolve(".junie"))
    Files.createDirectories(fixture.home.resolve(".cursor"))
    val plan =
      planInstallForTest(
        fixture.request(agentSelection = InstallAgentSelection(mode = InstallAgentSelectionMode.DETECTED)),
      )
    InstallPlanSchemaValidator.validate(buildInstallPlanWireMap(plan))
  }

  @Test
  fun `every telemetry level plan validates against the canonical schema`() {
    InstallTelemetryLevel.entries.forEach { level ->
      val fixture = setupFixture()
      val plan = planInstallForTest(fixture.request(telemetryLevel = level))
      InstallPlanSchemaValidator.validate(buildInstallPlanWireMap(plan))
    }
  }

  @Test
  fun `every windows symlink preflight plan validates against the canonical schema`() {
    windowsSymlinkPreflightCases().forEach { preflight ->
      val fixture = setupFixture()
      val plan = planInstallForTest(fixture.request(windowsSymlinkPreflight = preflight))
      InstallPlanSchemaValidator.validate(buildInstallPlanWireMap(plan))
    }
  }

  private fun setupFixture(): InstallPlanWireFixture {
    val repoRoot = Files.createTempDirectory("skillbill-install-plan-schema-repo").also(tempDirs::add)
    val home = Files.createTempDirectory("skillbill-install-plan-schema-home").also(tempDirs::add)
    seedBaseSkill(repoRoot, PACK_SIDECAR_PARENT_SKILL)
    seedBaseSkill(repoRoot, "bill-code-review")
    seedBaseSkill(repoRoot, "bill-code-check")
    seedPlatformPack(repoRoot, "kotlin", areaNames = listOf("architecture", "testing"))
    seedPlatformPack(repoRoot, "kmp", areaNames = listOf("architecture", "testing"))
    return InstallPlanWireFixture(repoRoot = repoRoot, home = home)
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

  private data class InstallPlanWireFixture(
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
