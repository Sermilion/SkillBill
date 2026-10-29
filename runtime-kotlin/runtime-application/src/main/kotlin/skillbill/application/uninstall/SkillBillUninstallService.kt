package skillbill.application.uninstall

import me.tatarka.inject.annotations.Inject
import skillbill.application.scaffold.InstallAgentService
import skillbill.application.uninstall.model.DesktopRemoval
import skillbill.application.uninstall.model.LauncherRemoval
import skillbill.application.uninstall.model.UninstallPlan
import skillbill.application.uninstall.model.UninstallRequest
import skillbill.application.uninstall.model.UninstallResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.install.nativeagent.InstallNativeAgentLinkPort
import skillbill.ports.system.HostPlatformPort
import skillbill.ports.system.UninstallPathsPort
import java.nio.file.Path

@Inject
class SkillBillUninstallService(
  private val installAgentService: InstallAgentService,
  private val installNativeAgentLinkPort: InstallNativeAgentLinkPort,
  private val installMcpRegistrationPort: InstallMcpRegistrationPort,
  private val uninstallFileSystem: UninstallPathsPort,
  private val hostPlatform: HostPlatformPort,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun plan(request: UninstallRequest): UninstallPlan {
    val home = request.home
    val stateRoot = request.stateRoot
    val skillNames = installedSkillNames(uninstallFileSystem, stateRoot.resolve("installed-skills"))
    val legacyNames = legacySkillNames(skillNames)
    val claudeTargets =
      installAgentService.claudeRoots(home, request.environment).flatMap { root ->
        listOf(root.resolve("skills"), root.resolve("commands"))
      }
    val codexTargets =
      installAgentService.codexRoots(home, request.environment)
        .map { root -> root.resolve("skills") }
    val agentTargets =
      listOf(
        home.resolve(".copilot/skills"),
        home.resolve(".agents/skills"),
        home.resolve(".junie/skills"),
        home.resolve(".cursor/skills"),
      ) + claudeTargets + codexTargets
    val stateRuntimeRoot = stateRoot.resolve("runtime")
    val binDir = request.environment["SKILL_BILL_BIN_DIR"]?.let(Path::of) ?: home.resolve(".local/bin")
    return UninstallPlan(
      home = home,
      stateRoot = stateRoot,
      skillNames = skillNames,
      legacyNames = legacyNames,
      agentTargets = agentTargets.distinct(),
      nativeSourceRoots = listOf(stateRoot.resolve("platform-packs"), stateRoot.resolve("skills")),
      mcpAgents = listOf("claude", "codex", "junie", "cursor"),
      launchers =
        listOf(
          LauncherRemoval(binDir.resolve("skill-bill"), stateRuntimeRoot.resolve("runtime-cli/bin/runtime-cli")),
          LauncherRemoval(binDir.resolve("skill-bill-mcp"), stateRuntimeRoot.resolve("runtime-mcp/bin/runtime-mcp")),
        ),
      desktop =
        desktopPlan(
          home = home,
          binDir = binDir,
          desktopAppDir = request.desktopAppDir,
          environment = request.environment,
          os = currentOs(hostPlatform.osName),
        ),
      environment = request.environment,
    )
  }

  fun apply(plan: UninstallPlan): UninstallResult {
    val removed = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    val recorder = UninstallMutationRecorder(diagnostics)

    cleanupAgentInstallTargets(plan, installAgentService, removed, skipped, recorder)
    cleanupNativeAgentInstallLinks(plan, installNativeAgentLinkPort, uninstallFileSystem, removed, recorder)
    cleanupMcpRegistrations(plan, installMcpRegistrationPort, removed, recorder)

    plan.launchers.forEach { launcher ->
      removeLauncher(uninstallFileSystem, launcher, removed, skipped, recorder)
    }
    removeDesktop(uninstallFileSystem, plan.desktop, removed, skipped, recorder)
    uninstallFileSystem.withConfigTransaction(plan.home, plan.environment) {
      removeInstallState(
        uninstallFileSystem,
        plan.stateRoot,
        removed,
        recorder,
        InstallStateRemovalOptions(uninstallFileSystem.configLockFilePaths(plan.home, plan.environment)),
      )
    }

    return UninstallResult(
      failed = recorder.failed(),
      removed = removed,
      skipped = skipped,
      warnings = recorder.failureMessages(),
    )
  }

  fun clearInstallState(
    stateRoot: Path,
    home: Path,
    environment: Map<String, String>,
    preserveGeneratedOnly: Boolean,
  ): UninstallResult {
    val removed = mutableListOf<String>()
    val recorder = UninstallMutationRecorder(diagnostics)
    if (uninstallFileSystem.exists(stateRoot)) {
      uninstallFileSystem.withConfigTransaction(home, environment) {
        removeInstallState(
          uninstallFileSystem,
          stateRoot,
          removed,
          recorder,
          InstallStateRemovalOptions(uninstallFileSystem.configLockFilePaths(home, environment), preserveGeneratedOnly),
        )
      }
    }
    return UninstallResult(recorder.failed(), removed, emptyList(), recorder.failureMessages())
  }
}

private val STAGED_SKILL_DIRECTORY = Regex("""^(.+)-[0-9a-f]{16}$""")

private val RENAMED_SKILL_PAIRS =
  listOf(
    "bill-kotlin-code-review-correctness" to "bill-kotlin-code-review-platform-correctness",
    "bill-kmp-code-review-correctness" to "bill-kmp-code-review-platform-correctness",
  )

private fun installedSkillNames(
  fileSystem: UninstallPathsPort,
  installedSkillsRoot: Path,
): List<String> {
  val names = mutableSetOf<String>()
  fileSystem.listImmediateDirectoryNames(installedSkillsRoot).forEach { name ->
    val match = STAGED_SKILL_DIRECTORY.matchEntire(name)
    if (match != null && !name.startsWith("native-agents-")) {
      names += match.groupValues[1]
    }
  }
  return names.sorted()
}

private fun legacySkillNames(skillNames: List<String>): List<String> {
  val names = mutableSetOf(".bill-shared")
  skillNames.filter { it.startsWith("bill-") }.forEach { skill ->
    names += "mdp-${skill.removePrefix("bill-")}"
  }
  RENAMED_SKILL_PAIRS.forEach { (oldName, newName) ->
    names += oldName
    names += "mdp-${oldName.removePrefix("bill-")}"
    names += "mdp-${newName.removePrefix("bill-")}"
  }
  return names.sorted()
}

private fun desktopPlan(
  home: Path,
  binDir: Path,
  desktopAppDir: String?,
  environment: Map<String, String>,
  os: DesktopOs,
): DesktopRemoval {
  val appDir = desktopAppDir?.let(Path::of) ?: defaultDesktopAppDir(home, environment, os)
  val executable =
    when (os) {
      DesktopOs.WINDOWS -> appDir.resolve("SkillBill.exe")
      DesktopOs.MAC,
      DesktopOs.LINUX,
      -> appDir.resolve("bin/skillbill-desktop")
    }
  val launcher =
    when (os) {
      DesktopOs.WINDOWS -> null
      DesktopOs.MAC,
      DesktopOs.LINUX,
      -> LauncherRemoval(binDir.resolve("skillbill-desktop"), executable)
    }
  val dataHome = environment["XDG_DATA_HOME"]?.let(Path::of) ?: home.resolve(".local/share")
  val linuxFiles =
    if (os == DesktopOs.LINUX) {
      listOf(
        dataHome.resolve("applications/skillbill.desktop"),
        dataHome.resolve("icons/hicolor/256x256/apps/skillbill.png"),
      )
    } else {
      emptyList()
    }
  val windowsLauncher =
    if (os == DesktopOs.WINDOWS) {
      listOf(binDir.resolve("skillbill-desktop.cmd"))
    } else {
      emptyList()
    }
  return DesktopRemoval(launcher = launcher, files = linuxFiles + windowsLauncher, directories = listOf(appDir))
}

private fun defaultDesktopAppDir(
  home: Path,
  environment: Map<String, String>,
  os: DesktopOs,
): Path =
  when (os) {
    DesktopOs.MAC -> Path.of("/Applications/SkillBill.app")
    DesktopOs.WINDOWS ->
      environment["LOCALAPPDATA"]?.let(Path::of)
        ?.resolve("SkillBill/Desktop/SkillBill")
        ?: home.resolve("AppData/Local/SkillBill/Desktop/SkillBill")
    DesktopOs.LINUX ->
      (environment["XDG_DATA_HOME"]?.let(Path::of) ?: home.resolve(".local/share"))
        .resolve("skillbill/desktop/SkillBill")
  }

private fun currentOs(rawOsName: String): DesktopOs {
  val osName = rawOsName.lowercase()
  return when {
    "mac" in osName || "darwin" in osName -> DesktopOs.MAC
    "win" in osName -> DesktopOs.WINDOWS
    else -> DesktopOs.LINUX
  }
}

private enum class DesktopOs {
  LINUX,
  MAC,
  WINDOWS,
}
