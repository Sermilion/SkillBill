package skillbill.infrastructure.skills.install.nativeagent

import skillbill.infrastructure.skills.install.nativeagent.link.uninstallNativeAgentFiles
import skillbill.infrastructure.skills.nativeagent.discovery.discoverNativeAgentFilesByDir
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import java.nio.file.Path

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
