package skillbill.infrastructure.skills.nativeagent

import skillbill.config.model.RepoLocalConfig
import skillbill.infrastructure.host.system.FileSystemRepoLocalConfig
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentCompositionContext
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentCompositionTarget
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentSource
import skillbill.infrastructure.skills.nativeagent.composition.composeNativeAgentSource
import skillbill.infrastructure.skills.nativeagent.composition.resolveNativeAgentCompositionTarget
import skillbill.infrastructure.skills.nativeagent.platformpack.InstallNativeAgentPlatformPackLoader
import skillbill.infrastructure.skills.scaffold.authoring.renderAuthoredContentBody
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.projectstore.FixedProjectStorePort
import java.nio.file.Files
import java.nio.file.Path

internal fun testNativeAgentCompositionContext(repoRoot: Path): NativeAgentCompositionContext {
  val normalizedRoot = repoRoot.toAbsolutePath().normalize()
  val projectStore = FixedProjectStorePort(Files.createTempDirectory("skillbill-native-agent-store"))
  val budget =
    runCatching {
      FileSystemRepoLocalConfig(NoopRuntimeDiagnostics, projectStore)
        .readRepoLocalConfig(ReadRepoLocalConfigRequest(normalizedRoot))
        .config
        .reviewContextBudget
        .maxLaneLaunchBytes
    }.getOrDefault(RepoLocalConfig.defaults().reviewContextBudget.maxLaneLaunchBytes)
  return NativeAgentCompositionContext(
    reviewContextBudgetBytes = budget,
    renderGovernedBody = ::renderAuthoredContentBody,
    packLoader = InstallNativeAgentPlatformPackLoader,
  )
}

internal fun testComposeNativeAgentSource(
  repoRoot: Path,
  source: NativeAgentSource,
): NativeAgentSource {
  val context = testNativeAgentCompositionContext(repoRoot)
  return composeNativeAgentSource(
    repoRoot,
    source,
    context,
  )
}

internal fun testResolveNativeAgentCompositionTarget(
  repoRoot: Path,
  source: NativeAgentSource,
): NativeAgentCompositionTarget? {
  val context = testNativeAgentCompositionContext(repoRoot)
  return resolveNativeAgentCompositionTarget(repoRoot, source, context.packLoader)
}
