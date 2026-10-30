package skillbill.infrastructure.skills.install.nativeagent

import skillbill.infrastructure.host.jvm.resolveUserHome
import skillbill.infrastructure.skills.nativeagent.discovery.discoverNativeAgentFilesByDir
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import java.nio.file.Path

internal fun discoverCursorAgentMarkdown(
  platformPacksRoot: Path,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> =
  discoverNativeAgentFilesByDir(
    platformPacksRoot = platformPacksRoot,
    skillsRoot = skillsRoot,
    selectedPlatforms = selectedPlatforms,
    directoryName = NativeAgentProvider.Cursor.directoryName,
    extension = NativeAgentProvider.Cursor.extension,
  )

internal fun uninstallCursorAgentMarkdown(
  platformPacksRoot: Path,
  home: Path? = null,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> {
  val resolvedHome = home ?: resolveUserHome(null)
  return uninstallNativeAgentFiles(
    discoverCursorAgentMarkdown(platformPacksRoot, skillsRoot, selectedPlatforms),
    NativeAgentProvider.Cursor.homeAgentDirs(resolvedHome),
  )
}
