package skillbill.application.uninstall

import skillbill.application.scaffold.InstallAgentService
import skillbill.application.uninstall.model.DesktopRemoval
import skillbill.application.uninstall.model.LauncherRemoval
import skillbill.application.uninstall.model.OwnedFileRemoval
import skillbill.application.uninstall.model.UninstallPlan
import skillbill.error.core.catchingNonCancellation
import skillbill.install.model.McpRegistrationOutcome
import skillbill.install.model.SupportedAgent
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.install.mcp.model.InstallMcpProfileEnvironment
import skillbill.ports.install.mcp.model.InstallMcpUnregistrationRequest
import skillbill.ports.install.model.NativeAgentLinkProvider
import skillbill.ports.install.model.NativeAgentLinkRequest
import skillbill.ports.install.nativeagent.InstallNativeAgentLinkPort
import skillbill.ports.install.nativeagent.model.InstallNativeAgentLinkOperationRequest
import skillbill.ports.install.nativeagent.model.InstallNativeAgentPreservationRequest
import skillbill.ports.system.UninstallPathsPort
import java.nio.file.Path

internal const val MANAGED_INSTALL_MARKER = "Managed by skill-bill install.sh"

internal fun cleanupAgentInstallTargets(
  plan: UninstallPlan,
  installAgentService: InstallAgentService,
  removed: MutableList<String>,
  skipped: MutableList<String>,
  recorder: UninstallMutationRecorder,
) {
  plan.agentTargets.forEach { target ->
    catchingNonCancellation {
      installAgentService.cleanupAgentTarget(
        targetDir = target,
        skillNames = plan.skillNames,
        legacyNames = plan.legacyNames,
        managedInstallMarker = MANAGED_INSTALL_MARKER,
        home = plan.home,
      )
    }.onSuccess { cleanup ->
      removed += cleanup.removed.map(Path::toString)
      skipped += cleanup.skipped.map(Path::toString)
    }.onFailure { error ->
      recorder.recordFailure("agent cleanup failed for $target", error)
    }
  }
}

internal fun cleanupNativeAgentInstallLinks(
  plan: UninstallPlan,
  installNativeAgentLinkPort: InstallNativeAgentLinkPort,
  removed: MutableList<String>,
  recorder: UninstallMutationRecorder,
): NativeAgentCleanupResult {
  val request =
    NativeAgentLinkRequest(
      platformPacksRoot = plan.stateRoot.resolve("platform-packs"),
      skillsRoot = plan.stateRoot.resolve("skills"),
      home = plan.home,
      environment = plan.environment,
    )
  val retainedPaths = mutableSetOf<Path>()
  var complete = true
  NativeAgentLinkProvider.entries.forEach { provider ->
    catchingNonCancellation {
      installNativeAgentLinkPort.unlinkNativeAgents(
        InstallNativeAgentLinkOperationRequest(
          provider = provider,
          linkRequest = request,
          includeAllSourceRoots = true,
        ),
      )
    }.onSuccess { result ->
      removed += result.unlinked.map(Path::toString)
      retainedPaths += result.retainedPaths
    }
      .onFailure { error ->
        complete = false
        recorder.recordFailure("native agent cleanup failed for ${provider.name.lowercase()}", error)
      }
  }
  val preservation =
    discoverNativeAgentPreservationTargets(
      plan.stateRoot,
      plan.home,
      plan.environment,
      installNativeAgentLinkPort,
      recorder,
    )
  retainedPaths += preservation.retainedPaths
  return NativeAgentCleanupResult(complete && preservation.complete, retainedPaths.toList())
}

internal fun discoverNativeAgentPreservationTargets(
  stateRoot: Path,
  home: Path,
  environment: Map<String, String>,
  installNativeAgentLinkPort: InstallNativeAgentLinkPort,
  recorder: UninstallMutationRecorder,
): NativeAgentCleanupResult =
  catchingNonCancellation {
    installNativeAgentLinkPort.discoverPreservedTargets(
      InstallNativeAgentPreservationRequest(stateRoot, home, environment),
    )
  }.fold(
    onSuccess = { result -> NativeAgentCleanupResult(true, result.retainedPaths) },
    onFailure = { error ->
      recorder.recordFailure("native agent preservation discovery failed", error)
      NativeAgentCleanupResult(false, emptyList())
    },
  )

internal data class NativeAgentCleanupResult(val complete: Boolean, val retainedPaths: List<Path>)

internal fun cleanupMcpRegistrations(
  plan: UninstallPlan,
  installMcpRegistrationPort: InstallMcpRegistrationPort,
  removed: MutableList<String>,
  recorder: UninstallMutationRecorder,
) {
  plan.mcpAgents.forEach { agent ->
    catchingNonCancellation {
      installMcpRegistrationPort.unregisterMcp(
        InstallMcpUnregistrationRequest(
          agent = agent,
          home = plan.home,
          profileEnvironment = InstallMcpProfileEnvironment.from(plan.environment),
          expectedRuntimeMcpBin = if (agent == SupportedAgent.OPENCODE.id) plan.runtimeMcpBinIdentity else null,
        ),
      ).outcome
    }.onSuccess { outcome ->
      when (outcome) {
        is McpRegistrationOutcome.Applied ->
          if (outcome.mutation.changed) removed += outcome.mutation.configPath.toString()
        is McpRegistrationOutcome.ProfilesFailed -> {
          removed += outcome.succeeded.filter { it.changed }.map { it.configPath.toString() }
          recorder.recordFailure("MCP cleanup failed for $agent", outcome.message)
        }
      }
    }.onFailure { error ->
      recorder.recordFailure("MCP cleanup failed for $agent", error)
    }
  }
}

internal fun removeLauncher(
  fileSystem: UninstallPathsPort,
  launcher: LauncherRemoval,
  removed: MutableList<String>,
  skipped: MutableList<String>,
  recorder: UninstallMutationRecorder,
) {
  if (!fileSystem.exists(launcher.path) && !fileSystem.isSymbolicLink(launcher.path)) {
    return
  }
  val owned = launcherIsOwned(fileSystem, launcher, recorder)
  if (!owned) {
    skipped += "${launcher.path} (not an owned launcher)"
    return
  }
  catchingNonCancellation { fileSystem.deleteIfExists(launcher.path) }
    .onSuccess { deleted ->
      if (deleted) {
        removed += launcher.path.toString()
      } else {
        recorder.recordFailure("could not remove launcher ${launcher.path}", "entry disappeared before deletion")
      }
    }
    .onFailure { error ->
      recorder.recordFailure("could not remove launcher ${launcher.path}", error)
    }
}

private fun launcherIsOwned(
  fileSystem: UninstallPathsPort,
  launcher: LauncherRemoval,
  recorder: UninstallMutationRecorder,
): Boolean {
  return if (fileSystem.isSymbolicLink(launcher.path)) {
    val expectedTarget = launcher.expectedTarget ?: return false
    val target =
      catchingNonCancellation { fileSystem.readSymbolicLink(launcher.path) }.getOrElse { error ->
        recorder.recordFailure("could not read launcher ${launcher.path}", error)
        return false
      }
    target == expectedTarget
  } else {
    val marker = launcher.managedMarker
    if (marker == null || !fileSystem.isRegularFile(launcher.path)) {
      false
    } else {
      catchingNonCancellation { fileSystem.readRegularFile(launcher.path) }.getOrElse { error ->
        recorder.recordFailure("could not inspect launcher ${launcher.path}", error)
        return false
      }.let { contents -> marker in contents && launcher.requiredLines.all { it in contents.lineSequence().toList() } }
    }
  }
}

internal fun removeDesktop(
  fileSystem: UninstallPathsPort,
  desktop: DesktopRemoval,
  removed: MutableList<String>,
  skipped: MutableList<String>,
  recorder: UninstallMutationRecorder,
) {
  var retainLaunchers = false
  desktop.directories.forEach { directory ->
    val linked =
      desktop.launchers.any { launcher ->
        directory.requiredFiles.any { executable ->
          (
            launcher.expectedTarget == executable ||
              "\"${executable.toString().replace("/", "\\")}\" %*" in launcher.requiredLines
          ) &&
            launcherIsOwned(fileSystem, launcher, recorder)
        }
      }
    val owned =
      linked && fileSystem.isDirectory(directory.path) && directory.requiredFiles.all(fileSystem::isRegularFile)
    if (!owned) {
      if (fileSystem.exists(directory.path)) skipped += "${directory.path} (desktop ownership was not confirmed)"
    } else {
      removeRecursively(fileSystem, directory.path, removed, recorder)
      if (fileSystem.exists(directory.path)) retainLaunchers = true
    }
  }
  if (!retainLaunchers) {
    desktop.launchers.forEach { launcher -> removeLauncher(fileSystem, launcher, removed, skipped, recorder) }
  }
  desktop.files.forEach { file ->
    removeOwnedFile(fileSystem, file, removed, skipped, recorder)
  }
}

private fun removeOwnedFile(
  fileSystem: UninstallPathsPort,
  file: OwnedFileRemoval,
  removed: MutableList<String>,
  skipped: MutableList<String>,
  recorder: UninstallMutationRecorder,
) {
  if (!fileSystem.exists(file.path) && !fileSystem.isSymbolicLink(file.path)) return
  if (fileSystem.isSymbolicLink(file.path) || !fileSystem.isRegularFile(file.path)) {
    skipped += "${file.path} (not an owned regular file)"
    return
  }
  val contents =
    catchingNonCancellation { fileSystem.readRegularFile(file.path) }.getOrElse { error ->
      recorder.recordFailure("could not inspect ${file.path}", error)
      return
    }
  val lines = contents.lineSequence().toList()
  if (!file.requiredLines.all { it in lines } || !file.requiredLineGroups.all { group -> group.any { it in lines } }) {
    skipped += "${file.path} (ownership did not match)"
    return
  }
  catchingNonCancellation { fileSystem.deleteIfExists(file.path) }
    .onSuccess { deleted ->
      if (deleted) {
        removed += file.path.toString()
      } else {
        recorder.recordFailure("could not remove ${file.path}", "entry disappeared before deletion")
      }
    }
    .onFailure { error -> recorder.recordFailure("could not remove ${file.path}", error) }
}

internal fun removeRecursively(
  fileSystem: UninstallPathsPort,
  path: Path,
  removed: MutableList<String>,
  recorder: UninstallMutationRecorder,
) {
  if (!fileSystem.exists(path) && !fileSystem.isSymbolicLink(path)) {
    return
  }
  catchingNonCancellation { fileSystem.removeTree(path) }
    .onSuccess { entries -> entries.forEach { entry -> removed += entry.toString() } }
    .onFailure { error ->
      recorder.recordFailure("could not remove $path", error)
    }
}

internal data class InstallStateRemovalOptions(
  val retainedPaths: Collection<Path>,
  val preserveGeneratedOnly: Boolean = false,
)

internal fun removeInstallState(
  fileSystem: UninstallPathsPort,
  stateRoot: Path,
  removed: MutableList<String>,
  recorder: UninstallMutationRecorder,
  options: InstallStateRemovalOptions,
) {
  val retainedPaths = options.retainedPaths
  if (options.preserveGeneratedOnly) {
    listOf(
      "installed-skills",
      ".candidate-source",
      "native-agent-link-inventory.json",
      "native-agent-link-inventory.json.lock",
    ).forEach { name -> removeRecursivelyExcept(fileSystem, stateRoot.resolve(name), retainedPaths, removed, recorder) }
    return
  }
  val entries =
    catchingNonCancellation { fileSystem.listImmediateDirectoryNames(stateRoot) }.getOrElse { error ->
      recorder.recordFailure("could not list install state under $stateRoot", error)
      return
    }
  entries.filterNot { name ->
    name == "backups" || name == "projects" || DATABASE_STATE_NAME.matches(name)
  }.forEach { name ->
    removeRecursivelyExcept(fileSystem, stateRoot.resolve(name), retainedPaths, removed, recorder)
  }
}

private fun removeRecursivelyExcept(
  fileSystem: UninstallPathsPort,
  path: Path,
  retainedPaths: Collection<Path>,
  removed: MutableList<String>,
  recorder: UninstallMutationRecorder,
) {
  if (!fileSystem.exists(path) && !fileSystem.isSymbolicLink(path)) return
  catchingNonCancellation { fileSystem.removeTreeExcept(path, retainedPaths) }
    .onSuccess { entries -> entries.forEach { entry -> removed += entry.toString() } }
    .onFailure { error ->
      recorder.recordFailure("could not remove $path", error)
    }
}

private val DATABASE_STATE_NAME = Regex(""".*\.db(?:-wal|-shm|-journal)?$""")
