package skillbill.infrastructure.skills.install.nativeagent

import skillbill.config.model.RepoLocalConfig
import skillbill.infrastructure.skills.nativeagent.platformpack.InstallNativeAgentPlatformPackLoader
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentCompositionContext
import skillbill.infrastructure.skills.scaffold.authoring.renderAuthoredContentBody
import java.nio.file.Path

internal fun installNativeAgentCompositionContext(
  additionalPackRoots: List<Path> = emptyList(),
): NativeAgentCompositionContext =
  NativeAgentCompositionContext(
    reviewContextBudgetBytes = RepoLocalConfig.defaults().reviewContextBudget.maxLaneLaunchBytes,
    renderGovernedBody = ::renderAuthoredContentBody,
    packLoader = InstallNativeAgentPlatformPackLoader,
    additionalPackRoots = additionalPackRoots,
  )
