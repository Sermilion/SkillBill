package skillbill.infrastructure.skills.install

import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
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
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.AfterTest

open class InstallPlanBuilderTestSupport {
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

  protected fun setupPlanFixture(): PlanFixture {
    val repoRoot = Files.createTempDirectory("skillbill-install-plan-repo").also(tempDirs::add)
    val home = Files.createTempDirectory("skillbill-install-plan-home").also(tempDirs::add)
    seedBaseSkill(repoRoot, PACK_SIDECAR_PARENT_SKILL)
    seedBaseSkill(repoRoot, "bill-code-review")
    seedBaseSkill(repoRoot, "bill-code-check")
    seedBaseSkill(repoRoot, "bill-update-check")
    seedPlatformPack(repoRoot, "kotlin", areaNames = listOf("architecture", "testing"))
    seedPlatformPack(repoRoot, "kmp", areaNames = listOf("architecture", "testing"))
    return PlanFixture(repoRoot = repoRoot, home = home)
  }

  protected fun seedBaseSkill(
    repoRoot: Path,
    skillName: String,
  ) {
    seedBaseSkillAt(repoRoot.resolve("skills"), skillName)
  }

  protected fun seedBaseSkillAt(
    skillsRoot: Path,
    skillName: String,
  ) {
    val skillDir = skillsRoot.resolve(skillName)
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      |---
      |name: $skillName
      |description: Test skill.
      |---
      |
      |## Execution
      |
      |Test body.
      |
      """.trimMargin(),
    )
  }

  protected fun seedPlatformPack(
    repoRoot: Path,
    slug: String,
    areaNames: List<String> = listOf("architecture"),
    pointerTarget: String? = null,
  ) {
    seedConformingPlatformPack(
      repoRoot = repoRoot,
      slug = slug,
      areaNames = areaNames.ifEmpty { listOf("architecture") },
      baselinePointerTarget = pointerTarget,
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
      appendLine("# $name")
      appendLine()
      appendLine("Test body.")
    }

  protected fun seedSkillClass(
    repoRoot: Path,
    skillName: String,
    pointers: List<String>,
  ) {
    val classRoot = repoRoot.resolve("orchestration/skill-classes")
    Files.createDirectories(classRoot)
    Files.writeString(
      classRoot.resolve("install-plan-test.yaml"),
      """
      |class: install-plan-test
      |contract_version: "1.8"
      |matchers:
      |  - exact: $skillName
      |pointers:
      |${pointers.joinToString("\n") { pointer -> "  - $pointer" }}
      |
      """.trimMargin(),
    )
  }

  protected fun seedSupportTarget(
    repoRoot: Path,
    relativePath: String,
  ) {
    val target = repoRoot.resolve(relativePath)
    Files.createDirectories(target.parent)
    Files.writeString(target, "# Support target\n")
  }

  protected fun snapshotTree(root: Path): Map<String, String> {
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

  protected fun declareCodeReviewFallback(
    repoRoot: Path,
    slug: String,
  ) {
    val manifest = repoRoot.resolve("platform-packs/$slug/platform.yaml")
    Files.writeString(
      manifest,
      Files.readString(manifest).replace(
        "declared_code_review_areas:",
        "fallback_capabilities:\n  - code-review\n\ndeclared_code_review_areas:",
      ),
    )
  }
}

data class PlanFixture(
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
