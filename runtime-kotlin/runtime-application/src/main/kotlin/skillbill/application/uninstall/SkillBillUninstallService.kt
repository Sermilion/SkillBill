package skillbill.application.uninstall

import me.tatarka.inject.annotations.Inject
import skillbill.application.scaffold.InstallAgentService
import skillbill.application.uninstall.model.DesktopRemoval
import skillbill.application.uninstall.model.LauncherRemoval
import skillbill.application.uninstall.model.OwnedDesktopDirectory
import skillbill.application.uninstall.model.OwnedFileRemoval
import skillbill.application.uninstall.model.UninstallPlan
import skillbill.application.uninstall.model.UninstallRequest
import skillbill.application.uninstall.model.UninstallResult
import skillbill.install.model.ListedSkillNames
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
    val skillNames =
      (
        installedSkillNames(uninstallFileSystem, stateRoot.resolve("installed-skills")) +
          ListedSkillNames.DISPATCHER + ListedSkillNames.PUBLIC_DISPATCHER
      ).distinct()
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
    val runtimeRoot = configuredPath(request.environment["SKILL_BILL_RUNTIME_DIR"]) ?: stateRoot.resolve("runtime")
    val binDir = request.environment["SKILL_BILL_BIN_DIR"]?.let(Path::of) ?: home.resolve(".local/bin")
    return UninstallPlan(
      home = home,
      stateRoot = stateRoot,
      skillNames = skillNames,
      legacyNames = legacyNames,
      agentTargets = agentTargets.distinct(),
      nativeSourceRoots = listOf(stateRoot.resolve("platform-packs"), stateRoot.resolve("skills")),
      mcpAgents = listOf("claude", "codex", "junie", "cursor"),
      launchers = runtimeLaunchers(binDir, runtimeRoot),
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
  names += ListedSkillNames.DISPATCHER
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
  val installRoot =
    configuredPath(desktopAppDir)
      ?: configuredPath(environment["SKILL_BILL_DESKTOP_APP_DIR"])
      ?: defaultDesktopInstallRoot(home, environment, os)
  val appDir =
    when (os) {
      DesktopOs.MAC -> installRoot.resolve("Sona.app")
      else -> installRoot.resolve("Sona")
    }
  val executable = sonaExecutable(os, appDir)
  val oldAppDir = legacyDesktopAppDir(home, environment, os)
  val oldExecutable =
    when (os) {
      DesktopOs.WINDOWS -> oldAppDir.resolve("SkillBill.exe")
      DesktopOs.MAC,
      DesktopOs.LINUX,
      -> oldAppDir.resolve("bin/skillbill-desktop")
    }
  val shellLegacyAppDir = installRoot.resolve("Skill Bill")
  val shellLegacyExecutable = shellLegacyAppDir.resolve("bin/Skill Bill")
  val launchers = desktopLaunchers(os, binDir, executable, oldExecutable, shellLegacyExecutable)
  val dataHome = environment["XDG_DATA_HOME"]?.let(Path::of) ?: home.resolve(".local/share")
  val files =
    if (os == DesktopOs.LINUX) desktopFiles(dataHome, binDir, appDir, oldAppDir, shellLegacyAppDir) else emptyList()
  return DesktopRemoval(
    launchers = launchers,
    files = files,
    directories =
      listOf(
        OwnedDesktopDirectory(appDir, listOf(executable)),
        OwnedDesktopDirectory(oldAppDir, listOf(oldExecutable)),
      ) +
        if (os == DesktopOs.LINUX) {
          listOf(OwnedDesktopDirectory(shellLegacyAppDir, listOf(shellLegacyExecutable)))
        } else {
          emptyList()
        },
  )
}

private fun desktopLaunchers(
  os: DesktopOs,
  binDir: Path,
  executable: Path,
  oldExecutable: Path,
  shellLegacyExecutable: Path,
): List<LauncherRemoval> {
  return when (os) {
    DesktopOs.WINDOWS ->
      listOf(
        windowsDesktopLauncher(binDir.resolve("sona.cmd"), executable),
        windowsDesktopLauncher(binDir.resolve("sona-app.cmd"), executable),
        windowsDesktopLauncher(binDir.resolve("skillbill-desktop.cmd"), oldExecutable),
      )
    DesktopOs.MAC ->
      listOf(
        LauncherRemoval(binDir.resolve("sona"), executable),
        LauncherRemoval(binDir.resolve("sona-app"), executable),
        LauncherRemoval(binDir.resolve("skillbill-desktop"), oldExecutable),
      )
    DesktopOs.LINUX ->
      listOf(
        LauncherRemoval(binDir.resolve("sona"), executable),
        LauncherRemoval(binDir.resolve("sona-app"), executable),
        LauncherRemoval(binDir.resolve("skillbill-desktop"), oldExecutable),
        LauncherRemoval(binDir.resolve("skillbill-desktop"), shellLegacyExecutable),
      )
  }
}

private fun desktopFiles(
  dataHome: Path,
  binDir: Path,
  appDir: Path,
  oldAppDir: Path,
  shellLegacyAppDir: Path,
): List<OwnedFileRemoval> {
  return listOf(
    OwnedFileRemoval(
      dataHome.resolve("applications/sona.desktop"),
      listOf(
        "Name=Sona",
        "Icon=$appDir/lib/Sona.png",
        "StartupWMClass=dev-skillbill-desktop-app-DesktopLauncherKt",
      ),
      listOf(listOf("Exec=${quoted(binDir.resolve("sona-app"))}", "Exec=${quoted(binDir.resolve("sona"))}")),
    ),
    OwnedFileRemoval(
      dataHome.resolve("applications/skillbill.desktop"),
      listOf("Name=Skill Bill"),
      listOf(
        listOf(
          "Icon=${oldAppDir.resolve("lib/Skill Bill.png")}",
          "Icon=${shellLegacyAppDir.resolve("lib/Skill Bill.png")}",
        ),
        listOf("Exec=${quoted(binDir.resolve("skillbill-desktop"))}"),
      ),
    ),
  )
}

private fun runtimeLaunchers(
  binDir: Path,
  stateRuntimeRoot: Path,
): List<LauncherRemoval> {
  val cli = stateRuntimeRoot.resolve("runtime-cli/bin/runtime-cli")
  val mcp = stateRuntimeRoot.resolve("runtime-mcp/bin/runtime-mcp")
  return listOf(
    LauncherRemoval(binDir.resolve("sona"), cli),
    LauncherRemoval(binDir.resolve("sona"), managedMarker = "# skill-bill managed launcher"),
    LauncherRemoval(binDir.resolve("skill-bill"), managedMarker = "# skill-bill managed launcher"),
    LauncherRemoval(binDir.resolve("skill-bill"), cli),
    LauncherRemoval(binDir.resolve("sona-mcp"), mcp),
    LauncherRemoval(binDir.resolve("skill-bill-mcp"), mcp),
  )
}

private fun windowsDesktopLauncher(
  path: Path,
  executable: Path,
): LauncherRemoval {
  val windowsExecutable = executable.toString().replace("/", "\\")
  return LauncherRemoval(
    path = path,
    managedMarker = "REM Managed by Sona install.sh",
    requiredLines = listOf("\"$windowsExecutable\" %*"),
  )
}

private fun quoted(path: Path): String = "\"${path.toString().replace("\\", "\\\\").replace("\"", "\\\"")}\""

private fun configuredPath(value: String?): Path? = value?.takeIf(String::isNotEmpty)?.let(Path::of)

private fun sonaExecutable(
  os: DesktopOs,
  appDir: Path,
): Path =
  when (os) {
    DesktopOs.MAC -> appDir.resolve("Contents/MacOS/Sona")
    DesktopOs.WINDOWS -> appDir.resolve("Sona.exe")
    DesktopOs.LINUX -> appDir.resolve("bin/Sona")
  }

private fun defaultDesktopInstallRoot(
  home: Path,
  environment: Map<String, String>,
  os: DesktopOs,
): Path =
  when (os) {
    DesktopOs.MAC -> home.resolve("Applications")
    DesktopOs.WINDOWS ->
      environment["LOCALAPPDATA"]?.let(Path::of)
        ?.resolve("SkillBill/Desktop")
        ?: home.resolve("AppData/Local/SkillBill/Desktop")
    DesktopOs.LINUX ->
      (environment["XDG_DATA_HOME"]?.let(Path::of) ?: home.resolve(".local/share"))
        .resolve("skillbill/desktop")
  }

private fun legacyDesktopAppDir(
  home: Path,
  environment: Map<String, String>,
  os: DesktopOs,
): Path =
  when (os) {
    DesktopOs.MAC -> Path.of("/Applications/SkillBill.app")
    DesktopOs.WINDOWS ->
      environment["LOCALAPPDATA"]?.let(Path::of)?.resolve("SkillBill/Desktop/SkillBill")
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
