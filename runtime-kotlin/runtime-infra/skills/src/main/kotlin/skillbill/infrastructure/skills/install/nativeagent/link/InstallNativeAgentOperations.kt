package skillbill.infrastructure.skills.install.nativeagent.link

import skillbill.infrastructure.host.jvm.resolveEnvironmentMap
import skillbill.infrastructure.host.jvm.resolveUserHome
import skillbill.infrastructure.skills.install.nativeagent.uninstallCodexAgentTomls
import skillbill.infrastructure.skills.install.nativeagent.uninstallCursorAgentMarkdown
import skillbill.infrastructure.skills.install.plan.CLAUDE_AGENTS_KIND
import skillbill.infrastructure.skills.install.plan.CURSOR_AGENTS_KIND
import skillbill.infrastructure.skills.install.plan.DEVIN_AGENTS_KIND
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.infrastructure.skills.nativeagent.support.agentPathExists
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.install.model.AgentTarget
import skillbill.install.model.SupportedAgent
import skillbill.ports.repository.toFileLocation
import java.nio.file.Path

data class NativeAgentLinkOutcome(
  val linked: List<Path>,
  val skipped: List<NativeAgentSkippedLink>,
)

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
  val nativeAgentLogicalNames: Set<String>? = null,
  val includeAllSourceRoots: Boolean = false,
)

object InstallNativeAgentOperations {
  fun linkClaudeAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome {
    val resolvedRequest = resolveNativeAgentLinkRequest(request)
    return linkProviderAgents(
      provider = NativeAgentProvider.Claude,
      request = resolvedRequest,
      detectTargets = { home ->
        NativeAgentProvider.Claude.homeAgentDirs(
          home,
          resolvedRequest.environment,
        ).map { AgentTarget(CLAUDE_AGENTS_KIND, it.toFileLocation()) }
      },
    )
  }

  fun unlinkClaudeAgents(request: NativeAgentLinkRequest): List<Path> =
    unlinkAgentsResult(SupportedAgent.CLAUDE, request).removed

  fun linkCodexAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome {
    val resolvedRequest = resolveNativeAgentLinkRequest(request)
    return linkProviderAgents(
      provider = NativeAgentProvider.Codex,
      request = resolvedRequest,
      detectTargets = { home ->
        NativeAgentProvider.Codex.activeHomeAgentDirs(home, resolvedRequest.environment).map {
          AgentTarget(NativeAgentProvider.Codex.directoryName, it.toFileLocation())
        }
      },
    )
  }

  fun unlinkCodexAgents(request: NativeAgentLinkRequest): List<Path> =
    unlinkAgentsResult(SupportedAgent.CODEX, request).removed

  fun linkCursorAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome {
    val resolvedRequest = resolveNativeAgentLinkRequest(request)
    return linkProviderAgents(
      provider = NativeAgentProvider.Cursor,
      request = resolvedRequest,
      detectTargets = { home ->
        val targetPath = NativeAgentProvider.Cursor.homeAgentDirs(home, resolvedRequest.environment).first()
        val root = home.resolve(requireNotNull(NativeAgentProvider.Cursor.supportedAgent.simpleHomeDirectory))
        val present =
          listOf(targetPath, root).map { path ->
            agentPathExists(path, "inspect Cursor native-agent directory")
          }.any { exists -> exists }
        if (present) {
          listOf(AgentTarget(CURSOR_AGENTS_KIND, targetPath.toFileLocation()))
        } else {
          emptyList()
        }
      },
    )
  }

  fun unlinkCursorAgents(request: NativeAgentLinkRequest): List<Path> =
    unlinkAgentsResult(SupportedAgent.CURSOR, request).removed

  fun linkDevinAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome {
    val resolvedRequest = resolveNativeAgentLinkRequest(request)
    return linkProviderAgents(
      provider = NativeAgentProvider.Devin,
      request = resolvedRequest,
      detectTargets = { home ->
        NativeAgentProvider.Devin.activeHomeAgentDirs(home, resolvedRequest.environment).map { path ->
          AgentTarget(DEVIN_AGENTS_KIND, path.toFileLocation())
        }
      },
    )
  }

  fun unlinkDevinAgents(request: NativeAgentLinkRequest): List<Path> =
    unlinkAgentsResult(SupportedAgent.DEVIN, request).removed

  fun linkOpenCodeAgents(request: NativeAgentLinkRequest): NativeAgentLinkOutcome {
    val resolvedRequest = resolveNativeAgentLinkRequest(request)
    return linkProviderAgents(
      provider = NativeAgentProvider.OpenCode,
      request = resolvedRequest,
      detectTargets = { home ->
        NativeAgentProvider.OpenCode.homeAgentDirs(home, resolvedRequest.environment).map {
          AgentTarget(NativeAgentProvider.OpenCode.directoryName, it.toFileLocation())
        }
      },
    )
  }

  fun unlinkOpenCodeAgents(request: NativeAgentLinkRequest): List<Path> =
    unlinkAgentsResult(SupportedAgent.OPENCODE, request).removed

  internal fun unlinkAgentsResult(
    provider: SupportedAgent,
    request: NativeAgentLinkRequest,
    journal: ProviderMutationJournal? = null,
  ): NativeAgentRemovalOutcome {
    val resolvedRequest = resolveNativeAgentLinkRequest(request)
    val outcome =
      unlinkProviderAgents(
        NativeAgentProvider.forSupportedAgent(provider),
        resolvedRequest,
        sharedJournal = journal,
      )
    if (journal != null) return outcome
    val legacyRemoved =
      when (provider) {
        SupportedAgent.CODEX ->
          uninstallCodexAgentTomls(
            resolvedRequest.platformPacksRoot,
            resolvedRequest.skillsRoot,
            resolvedRequest.selectedPlatforms,
            NativeAgentProvider.Codex.homeAgentDirs(requireNotNull(resolvedRequest.home), resolvedRequest.environment),
          )
        SupportedAgent.CURSOR ->
          uninstallCursorAgentMarkdown(
            resolvedRequest.platformPacksRoot,
            resolvedRequest.home,
            resolvedRequest.skillsRoot,
            resolvedRequest.selectedPlatforms,
          )
        SupportedAgent.CLAUDE, SupportedAgent.OPENCODE, SupportedAgent.DEVIN -> emptyList()
      }
    return outcome.copy(removed = legacyRemoved + outcome.removed)
  }
}

private fun resolveNativeAgentLinkRequest(request: NativeAgentLinkRequest): NativeAgentLinkRequest =
  request.copy(
    home = resolveUserHome(request.home),
    environment = resolveEnvironmentMap(request.environment),
  )
