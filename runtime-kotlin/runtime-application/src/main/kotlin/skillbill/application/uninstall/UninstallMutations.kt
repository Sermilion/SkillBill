package skillbill.application.uninstall

import skillbill.application.rethrowIfCooperativeCancellationOrInterruption
import skillbill.application.scaffold.InstallAgentService
import skillbill.application.uninstall.model.DesktopRemoval
import skillbill.application.uninstall.model.LauncherRemoval
import skillbill.application.uninstall.model.UninstallPlan
import skillbill.install.model.McpRegistrationOutcome
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.install.mcp.model.InstallMcpUnregistrationRequest
import skillbill.ports.install.model.NativeAgentLinkProvider
import skillbill.ports.install.model.NativeAgentLinkRequest
import skillbill.ports.install.nativeagent.InstallNativeAgentLinkPort
import skillbill.ports.install.nativeagent.model.InstallNativeAgentLinkOperationRequest
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
    runCatching {
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
      error.rethrowIfCooperativeCancellationOrInterruption()
      recorder.recordFailure("agent cleanup failed for $target", error)
    }
  }
}

internal fun cleanupNativeAgentInstallLinks(
  plan: UninstallPlan,
  installNativeAgentLinkPort: InstallNativeAgentLinkPort,
  uninstallFileSystem: UninstallPathsPort,
  removed: MutableList<String>,
  recorder: UninstallMutationRecorder,
) {
  if (!plan.nativeSourceRoots.any(uninstallFileSystem::exists)) {
    return
  }
  val request =
    NativeAgentLinkRequest(
      platformPacksRoot = plan.stateRoot.resolve("platform-packs"),
      skillsRoot = plan.stateRoot.resolve("skills"),
      home = plan.home,
    )
  NativeAgentLinkProvider.entries.forEach { provider ->
    runCatching {
      installNativeAgentLinkPort.unlinkNativeAgents(
        InstallNativeAgentLinkOperationRequest(
          provider = provider,
          linkRequest = request,
        ),
      )
    }.onSuccess { result -> removed += result.unlinked.map(Path::toString) }
      .onFailure { error ->
        error.rethrowIfCooperativeCancellationOrInterruption()
        recorder.recordFailure("native agent cleanup failed for ${provider.name.lowercase()}", error)
      }
  }
}

internal fun cleanupMcpRegistrations(
  plan: UninstallPlan,
  installMcpRegistrationPort: InstallMcpRegistrationPort,
  removed: MutableList<String>,
  recorder: UninstallMutationRecorder,
) {
  plan.mcpAgents.forEach { agent ->
    runCatching {
      installMcpRegistrationPort.unregisterMcp(
        InstallMcpUnregistrationRequest(
          agent = agent,
          home = plan.home,
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
      error.rethrowIfCooperativeCancellationOrInterruption()
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
  if (!fileSystem.isSymbolicLink(launcher.path)) {
    skipped += "${launcher.path} (not a symlink)"
    return
  }
  val target =
    runCatching { fileSystem.readSymbolicLink(launcher.path) }.getOrElse { error ->
      error.rethrowIfCooperativeCancellationOrInterruption()
      recorder.recordFailure("could not read launcher ${launcher.path}", error)
      return
    }
  if (target != launcher.expectedTarget) {
    skipped += "${launcher.path} (points to $target)"
    return
  }
  runCatching { fileSystem.deleteIfExists(launcher.path) }
    .onSuccess { removed += launcher.path.toString() }
    .onFailure { error ->
      error.rethrowIfCooperativeCancellationOrInterruption()
      recorder.recordFailure("could not remove launcher ${launcher.path}", error)
    }
}

internal fun removeDesktop(
  fileSystem: UninstallPathsPort,
  desktop: DesktopRemoval,
  removed: MutableList<String>,
  skipped: MutableList<String>,
  recorder: UninstallMutationRecorder,
) {
  desktop.launcher?.let { removeLauncher(fileSystem, it, removed, skipped, recorder) }
  desktop.files.forEach { file ->
    if (!fileSystem.exists(file)) return@forEach
    runCatching { fileSystem.deleteIfExists(file) }
      .onSuccess { removed += file.toString() }
      .onFailure { error ->
        error.rethrowIfCooperativeCancellationOrInterruption()
        recorder.recordFailure("could not remove $file", error)
      }
  }
  desktop.directories.forEach { directory -> removeRecursively(fileSystem, directory, removed, recorder) }
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
  runCatching { fileSystem.removeTree(path) }
    .onSuccess { entries -> entries.forEach { entry -> removed += entry.toString() } }
    .onFailure { error ->
      error.rethrowIfCooperativeCancellationOrInterruption()
      recorder.recordFailure("could not remove $path", error)
    }
}
