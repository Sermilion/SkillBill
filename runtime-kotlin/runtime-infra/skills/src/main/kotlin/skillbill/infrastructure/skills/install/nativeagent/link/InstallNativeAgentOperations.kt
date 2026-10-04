package skillbill.infrastructure.skills.install.nativeagent.link

import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.host.jvm.resolveUserHome
import skillbill.infrastructure.skills.install.nativeagent.uninstallCodexAgentTomls
import skillbill.infrastructure.skills.install.nativeagent.uninstallCursorAgentMarkdown
import skillbill.infrastructure.skills.install.nativeagent.uninstallJunieAgentMarkdown
import skillbill.infrastructure.skills.install.plan.CLAUDE_AGENTS_KIND
import skillbill.infrastructure.skills.install.plan.CURSOR_AGENTS_KIND
import skillbill.infrastructure.skills.install.plan.JUNIE_AGENTS_KIND
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.install.model.AgentTarget
import skillbill.ports.repository.toFileLocation
import java.nio.file.Files
import java.nio.file.Path

data class NativeAgentLinkOutcome(
  val linked: List<Path>,
  val skipped: List<NativeAgentSkippedLink>,
  val failure: NativeAgentLinkFailure? = null,
)

data class NativeAgentLinkFailure(val path: Path, val error: SkillBillRuntimeException)

data class NativeAgentSkippedLink(val path: Path, val reason: String)

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
  val catalogLoader: PlatformPackCatalogLoader? = null,
)

object InstallNativeAgentOperations {
  fun linkClaudeAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome {
    val resolvedHome = request.home ?: resolveUserHome(null)
    return linkProviderAgents(
      provider = NativeAgentProvider.Claude,
      request = request,
      detectTargets = {
        NativeAgentProvider.Claude.homeAgentDirs(
          resolvedHome,
        ).map { AgentTarget(CLAUDE_AGENTS_KIND, it.toFileLocation()) }
      },
    )
  }

  fun unlinkClaudeAgents(request: NativeAgentLinkRequest): List<Path> =
    unlinkProviderAgents(
      provider = NativeAgentProvider.Claude,
      request = request,
    )

  fun linkCodexAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome =
    linkProviderAgents(
      provider = NativeAgentProvider.Codex,
      request = request,
      detectTargets = { home ->
        NativeAgentProvider.Codex.activeHomeAgentDirs(home).map {
          AgentTarget(NativeAgentProvider.Codex.directoryName, it.toFileLocation())
        }
      },
    )

  fun unlinkCodexAgents(request: NativeAgentLinkRequest): List<Path> {
    val resolvedHome = request.home ?: resolveUserHome(null)
    val unlinkedFromCache = unlinkProviderAgents(provider = NativeAgentProvider.Codex, request = request)
    return uninstallCodexAgentTomls(
      request.platformPacksRoot,
      resolvedHome,
      request.skillsRoot,
      request.selectedPlatforms,
    ) + unlinkedFromCache
  }

  fun linkJunieAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome =
    linkProviderAgents(
      provider = NativeAgentProvider.Junie,
      request = request,
      detectTargets = { resolvedHome ->
        val targetPath = NativeAgentProvider.Junie.homeAgentDirs(resolvedHome).first()
        if (
          Files.exists(targetPath) ||
          Files.exists(
            resolvedHome.resolve(requireNotNull(NativeAgentProvider.Junie.supportedAgent.simpleHomeDirectory)),
          )
        ) {
          listOf(AgentTarget(JUNIE_AGENTS_KIND, targetPath.toFileLocation()))
        } else {
          emptyList()
        }
      },
    )

  fun unlinkJunieAgents(request: NativeAgentLinkRequest): List<Path> {
    val resolvedHome = request.home ?: resolveUserHome(null)
    val unlinkedFromCache = unlinkProviderAgents(provider = NativeAgentProvider.Junie, request = request)
    return uninstallJunieAgentMarkdown(
      request.platformPacksRoot,
      resolvedHome,
      request.skillsRoot,
      request.selectedPlatforms,
    ) +
      unlinkedFromCache
  }

  fun linkCursorAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome =
    linkProviderAgents(
      provider = NativeAgentProvider.Cursor,
      request = request,
      detectTargets = { resolvedHome ->
        val targetPath = NativeAgentProvider.Cursor.homeAgentDirs(resolvedHome).first()
        if (
          Files.exists(targetPath) ||
          Files.exists(
            resolvedHome.resolve(requireNotNull(NativeAgentProvider.Cursor.supportedAgent.simpleHomeDirectory)),
          )
        ) {
          listOf(AgentTarget(CURSOR_AGENTS_KIND, targetPath.toFileLocation()))
        } else {
          emptyList()
        }
      },
    )

  fun unlinkCursorAgents(request: NativeAgentLinkRequest): List<Path> {
    val resolvedHome = request.home ?: resolveUserHome(null)
    val unlinkedFromCache = unlinkProviderAgents(provider = NativeAgentProvider.Cursor, request = request)
    return uninstallCursorAgentMarkdown(
      request.platformPacksRoot,
      resolvedHome,
      request.skillsRoot,
      request.selectedPlatforms,
    ) +
      unlinkedFromCache
  }
}
