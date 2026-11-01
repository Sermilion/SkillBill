package skillbill.infrastructure.skills.install.nativeagent

import skillbill.infrastructure.skills.install.symlink.resolveSymlinkTarget
import skillbill.infrastructure.skills.nativeagent.discovery.discoverNativeAgentFilesByDir
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import java.nio.file.Files
import java.nio.file.Path

internal fun uninstallNativeAgentFiles(
  sources: List<Path>,
  candidateDirs: List<Path>,
): List<Path> {
  val removed = mutableListOf<Path>()
  sources.forEach { source ->
    val resolvedSource = source.toAbsolutePath().normalize()
    candidateDirs.forEach { targetDir ->
      val linkPath = targetDir.resolve(resolvedSource.fileName)
      val existingTarget = if (Files.isSymbolicLink(linkPath)) resolveSymlinkTarget(linkPath) else null
      if (existingTarget == resolvedSource) {
        Files.deleteIfExists(linkPath)
        removed.add(linkPath)
      }
    }
  }
  return removed
}

internal fun discoverCodexAgentTomls(
  platformPacksRoot: Path,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> =
  discoverNativeAgentFilesByDir(
    platformPacksRoot = platformPacksRoot,
    skillsRoot = skillsRoot,
    selectedPlatforms = selectedPlatforms,
    directoryName = NativeAgentProvider.Codex.directoryName,
    extension = NativeAgentProvider.Codex.extension,
  )

internal fun uninstallCodexAgentTomls(
  platformPacksRoot: Path,
  skillsRoot: Path?,
  selectedPlatforms: List<String>?,
  agentDirs: List<Path>,
): List<Path> {
  return uninstallNativeAgentFiles(
    discoverCodexAgentTomls(platformPacksRoot, skillsRoot, selectedPlatforms),
    agentDirs,
  )
}
