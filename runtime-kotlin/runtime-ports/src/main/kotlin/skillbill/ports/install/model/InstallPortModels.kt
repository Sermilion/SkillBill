package skillbill.ports.install.model

import java.nio.file.Path

data class InstallCleanupResult(
  val removed: List<Path>,
  val skipped: List<Path>,
)

enum class NativeAgentLinkProvider {
  CLAUDE,
  CODEX,
  JUNIE,
  CURSOR,
  OPENCODE,
  DEVIN,
}

data class NativeAgentLinkOverrides(
  val installCacheRoot: Path? = null,
  val sourceRoots: List<Path>? = null,
  val legacyManagedRoot: Path? = null,
)

data class NativeAgentLinkRequest(
  val platformPacksRoot: Path,
  val skillsRoot: Path? = null,
  val home: Path? = null,
  val selectedPlatforms: List<String>? = null,
  val overrides: NativeAgentLinkOverrides = NativeAgentLinkOverrides(),
  val environment: Map<String, String> = emptyMap(),
  val nativeAgentLogicalNames: Set<String>? = null,
)

data class NativeAgentLinkOutcome(
  val linked: List<Path>,
  val skipped: List<NativeAgentSkippedLink>,
)

data class NativeAgentSkippedLink(
  val path: Path,
  val reason: String,
)
