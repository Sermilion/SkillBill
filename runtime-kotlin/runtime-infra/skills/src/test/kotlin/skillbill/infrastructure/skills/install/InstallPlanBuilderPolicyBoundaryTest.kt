package skillbill.infrastructure.skills.install

import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
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
import skillbill.ports.repository.toFileLocation
import skillbill.testing.seedConformingPlatformPack
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallPlanBuilderPolicyBoundaryTest {
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
  fun `unselected platform declared content files are not materialized during planning`() {
    val fixture = setupPlanFixture()
    val outsideSkillDir = fixture.repoRoot.resolve("outside/bill-escaped-code-review")
    Files.createDirectories(outsideSkillDir)
    Files.writeString(outsideSkillDir.resolve("content.md"), content("bill-escaped-code-review"))
    seedPlatformPack(fixture.repoRoot, slug = "experimental")
    val manifest = fixture.repoRoot.resolve("platform-packs/experimental/platform.yaml")
    Files.writeString(
      manifest,
      Files.readString(manifest).replace(
        "baseline: \"code-review/bill-experimental-code-review/content.md\"",
        "baseline: \"../../outside/bill-escaped-code-review/content.md\"",
      ),
    )

    val plan =
      planInstallForTest(
        fixture.request(
          platformPackSelection =
            PlatformPackSelection(
              mode = PlatformPackSelectionMode.SELECTED,
              selectedSlugs = setOf("kotlin"),
            ),
        ),
      )

    assertTrue(plan.discoveredPlatformPacks.any { pack -> pack.slug == "experimental" && !pack.selected })
    assertEquals(listOf("kotlin"), plan.selectedPlatformSlugs)
    assertFalse(plan.skills.any { skill -> skill.platformSlug == "experimental" })
  }

  private fun setupPlanFixture(): PlanFixture {
    val repoRoot = Files.createTempDirectory("skillbill-install-plan-boundary-repo").also(tempDirs::add)
    val home = Files.createTempDirectory("skillbill-install-plan-boundary-home").also(tempDirs::add)
    seedBaseSkill(repoRoot, PACK_SIDECAR_PARENT_SKILL)
    seedBaseSkill(repoRoot, "bill-code-review")
    seedBaseSkill(repoRoot, "bill-code-check")
    seedPlatformPack(repoRoot, "kotlin")
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
  ) {
    seedConformingPlatformPack(repoRoot, slug)
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

  private data class PlanFixture(
    val repoRoot: Path,
    val home: Path,
  ) {
    private val runtimeInstallRoot: Path = home.resolve(".skill-bill/runtime")

    fun request(
      platformPackSelection: PlatformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.NONE),
    ): InstallPlanRequest =
      InstallPlanRequest(
        repoRoot = repoRoot.toFileLocation(),
        home = home.toFileLocation(),
        agentSelection =
          InstallAgentSelection(
            mode = InstallAgentSelectionMode.MANUAL,
            manualAgents = setOf(SupportedAgent.CODEX),
          ),
        platformPackSelection = platformPackSelection,
        telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
        mcpRegistrationChoice =
          McpRegistrationChoice(
            register = true,
            runtimeMcpBin = runtimeInstallRoot.resolve("runtime-mcp/bin/runtime-mcp").toFileLocation(),
          ),
        runtimeDistributionInputs = RuntimeDistributionInputs(runtimeInstallRoot = runtimeInstallRoot.toFileLocation()),
        targetPaths =
          InstallationTargetPaths(
            skillsRoot = repoRoot.resolve("skills").toFileLocation(),
            platformPacksRoot = repoRoot.resolve("platform-packs").toFileLocation(),
          ),
        windowsSymlinkPreflight =
          WindowsSymlinkPreflight(
            state = WindowsSymlinkPreflightState.NOT_WINDOWS,
            decision = WindowsSymlinkDecision.NOT_REQUIRED,
          ),
        environment = installTestEnvironment(home),
      )
  }
}
