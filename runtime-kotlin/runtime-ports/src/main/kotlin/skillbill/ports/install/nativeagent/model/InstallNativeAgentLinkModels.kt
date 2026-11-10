package skillbill.ports.install.nativeagent.model

import skillbill.ports.install.model.NativeAgentLinkOutcome
import skillbill.ports.install.model.NativeAgentLinkProvider
import skillbill.ports.install.model.NativeAgentLinkRequest
import java.nio.file.Path

data class InstallNativeAgentLinkOperationRequest(
  val provider: NativeAgentLinkProvider,
  val linkRequest: NativeAgentLinkRequest,
  val includeAllSourceRoots: Boolean = false,
)

data class InstallNativeAgentLinkOperationResult(
  val outcome: NativeAgentLinkOutcome,
)

data class InstallNativeAgentUnlinkOperationResult(
  val unlinked: List<Path>,
  val retainedPaths: List<Path> = emptyList(),
)
