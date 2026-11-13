package skillbill.cli.install.nativeagent

import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.tatarka.inject.annotations.Inject
import skillbill.cli.install.apply.completeNativeAgentLinkOutcome
import skillbill.cli.install.apply.refuseInstallMutationDuringGoalContinuation
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.model.CliRunInputs
import skillbill.ports.install.model.NativeAgentLinkProvider
import skillbill.ports.install.model.NativeAgentLinkRequest
import skillbill.ports.install.nativeagent.InstallNativeAgentLinkPort
import skillbill.ports.install.nativeagent.model.InstallNativeAgentLinkOperationRequest
import java.nio.file.Path

abstract class NativeAgentLinkCliCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val installNativeAgentLinkPort: InstallNativeAgentLinkPort,
  private val provider: NativeAgentLinkProvider,
  private val unlink: Boolean,
  name: String,
  help: String,
) : DocumentedCliCommand(name, help) {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation(inputs, commandName)) {
      return
    }
    val request =
      InstallNativeAgentLinkOperationRequest(
        provider = provider,
        linkRequest =
          NativeAgentLinkRequest(
            platformPacksRoot = Path.of(platformPacks),
            skillsRoot = skills?.let(Path::of),
            home = inputs.userHome,
            environment = inputs.environment,
            selectedPlatforms = platforms.ifEmpty { null },
          ),
      )
    if (unlink) {
      val removed = installNativeAgentLinkPort.unlinkNativeAgents(request).unlinked
      state.completeText(removed.joinToString("\n"), mapOf("removed" to removed.map(Path::toString)))
    } else {
      completeNativeAgentLinkOutcome(state, installNativeAgentLinkPort.linkNativeAgents(request).outcome)
    }
  }
}

@Inject
class InstallLinkOpenCodeAgentsCommand(
  state: CliRunState,
  inputs: CliRunInputs,
  port: InstallNativeAgentLinkPort,
) : NativeAgentLinkCliCommand(
    state,
    inputs,
    port,
    NativeAgentLinkProvider.OPENCODE,
    unlink = false,
    name = "link-opencode-agents",
    help = "Render and link OpenCode native subagent markdown from source agents.",
  )

@Inject
class InstallUnlinkOpenCodeAgentsCommand(
  state: CliRunState,
  inputs: CliRunInputs,
  port: InstallNativeAgentLinkPort,
) : NativeAgentLinkCliCommand(
    state,
    inputs,
    port,
    NativeAgentLinkProvider.OPENCODE,
    unlink = true,
    name = "unlink-opencode-agents",
    help = "Remove OpenCode native subagent markdown symlinks.",
  )

@Inject
class InstallLinkDevinAgentsCommand(
  state: CliRunState,
  inputs: CliRunInputs,
  port: InstallNativeAgentLinkPort,
) : NativeAgentLinkCliCommand(
    state,
    inputs,
    port,
    NativeAgentLinkProvider.DEVIN,
    unlink = false,
    name = "link-devin-agents",
    help = "Render and link Devin native subagent markdown from source agents.",
  )

@Inject
class InstallUnlinkDevinAgentsCommand(
  state: CliRunState,
  inputs: CliRunInputs,
  port: InstallNativeAgentLinkPort,
) : NativeAgentLinkCliCommand(
    state,
    inputs,
    port,
    NativeAgentLinkProvider.DEVIN,
    unlink = true,
    name = "unlink-devin-agents",
    help = "Remove Devin native subagent markdown symlinks.",
  )
