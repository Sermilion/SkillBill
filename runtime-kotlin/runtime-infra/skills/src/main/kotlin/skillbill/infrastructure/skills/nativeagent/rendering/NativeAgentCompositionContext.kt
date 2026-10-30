package skillbill.infrastructure.skills.nativeagent.rendering

import skillbill.infrastructure.skills.nativeagent.platformpack.NativeAgentPlatformPackLoader
import java.nio.file.Path

internal data class NativeAgentCompositionContext(
  val reviewContextBudgetBytes: Long,
  val renderGovernedBody: (Path, String) -> String,
  val packLoader: NativeAgentPlatformPackLoader,
  val additionalPackRoots: List<Path> = emptyList(),
)
