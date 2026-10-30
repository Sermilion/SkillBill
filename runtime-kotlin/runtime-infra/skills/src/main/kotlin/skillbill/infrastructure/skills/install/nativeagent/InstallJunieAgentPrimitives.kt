package skillbill.infrastructure.skills.install.nativeagent

import skillbill.infrastructure.host.jvm.resolveUserHome
import skillbill.infrastructure.skills.nativeagent.discovery.discoverNativeAgentFilesByDir
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import java.nio.file.Path

internal fun discoverJunieAgentMarkdown(
  platformPacksRoot: Path,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> =
  discoverNativeAgentFilesByDir(
    platformPacksRoot = platformPacksRoot,
    skillsRoot = skillsRoot,
    selectedPlatforms = selectedPlatforms,
    directoryName = NativeAgentProvider.Junie.directoryName,
    extension = NativeAgentProvider.Junie.extension,
  )

internal fun uninstallJunieAgentMarkdown(
  platformPacksRoot: Path,
  home: Path? = null,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> {
  val resolvedHome = home ?: resolveUserHome(null)
  return uninstallNativeAgentFiles(
    discoverJunieAgentMarkdown(platformPacksRoot, skillsRoot, selectedPlatforms),
    NativeAgentProvider.Junie.homeAgentDirs(resolvedHome),
  )
}
