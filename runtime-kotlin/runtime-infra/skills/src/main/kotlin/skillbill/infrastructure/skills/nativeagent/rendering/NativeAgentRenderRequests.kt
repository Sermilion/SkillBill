package skillbill.infrastructure.skills.nativeagent.rendering

import skillbill.infrastructure.host.jvm.resolveUserHome
import skillbill.infrastructure.skills.nativeagent.composition.NATIVE_AGENT_BUNDLE_FILE
import skillbill.infrastructure.skills.nativeagent.composition.NATIVE_AGENT_SOURCE_DIR
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentSource
import skillbill.infrastructure.skills.nativeagent.composition.parseNativeAgentSourceFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

internal data class NativeAgentRegenerationResult(
  val regeneratedFiles: List<Path>,
)

internal data class NativeAgentInstallRenderResult(
  val generatedFiles: List<Path>,
  val artifacts: List<NativeAgentRenderedArtifact>,
  val cacheRoot: Path,
)

internal data class NativeAgentRenderedArtifact(
  val logicalName: String,
  val path: Path,
  val contentDigest: String,
)

data class NativeAgentInstallRenderOverrides(
  val cacheRoot: Path? = null,
  val sourceRoots: List<Path>? = null,
  val beforeMutation: (Path) -> Unit = {},
  val afterTemporaryCreation: (Path) -> Unit = {},
)

internal data class NativeAgentInstallRenderRequest(
  val platformPacksRoot: Path,
  val skillsRoot: Path?,
  val selectedPlatforms: List<String>?,
  val provider: NativeAgentProvider,
  val home: Path,
  val compositionContext: NativeAgentCompositionContext,
  val overrides: NativeAgentInstallRenderOverrides = NativeAgentInstallRenderOverrides(),
)

internal data class NativeAgentRegenerationRequest(
  val repoRoot: Path,
  val compositionContext: NativeAgentCompositionContext,
  val skillNames: List<String> = emptyList(),
  val home: Path = resolveUserHome(null),
  val originalBytes: MutableMap<Path, ByteArray>? = null,
  val createdPaths: MutableList<Path>? = null,
)

internal fun discoverRepoNativeAgentSources(repoRoot: Path): List<Path> {
  return discoverRepoNativeAgentSourceFiles(repoRoot)
}

internal fun discoverRepoNativeAgentSourceEntries(repoRoot: Path): List<NativeAgentSource> {
  return discoverRepoNativeAgentSourceFiles(repoRoot).flatMap(::parseNativeAgentSourceFile)
}

internal fun discoverRepoNativeAgentSourceFiles(repoRoot: Path): List<Path> {
  val roots = listOf(repoRoot.resolve("skills"), repoRoot.resolve("platform-packs"))
  return roots.filter { it.isDirectory() }.flatMap { root ->
    Files.walk(root).use { stream ->
      stream
        .filter { file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) }
        .filter { file ->
          file.parent?.name == NATIVE_AGENT_SOURCE_DIR &&
            (file.fileName.toString().endsWith(".md") || file.fileName.toString() == NATIVE_AGENT_BUNDLE_FILE)
        }
        .toList()
    }
  }.sortedBy { it.toString() }
}
