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

@Inject
class InstallLinkClaudeAgentsCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val installNativeAgentLinkPort: InstallNativeAgentLinkPort,
) : DocumentedCliCommand("link-claude-agents", "Render and link Claude native subagent markdown from source agents.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation(inputs, "link-claude-agents")) {
      return
    }
    completeNativeAgentLinkOutcome(
      state,
      installNativeAgentLinkPort.linkNativeAgents(
        InstallNativeAgentLinkOperationRequest(
          provider = NativeAgentLinkProvider.CLAUDE,
          linkRequest = nativeAgentLinkRequest(),
        ),
      ).outcome,
    )
  }

  private fun nativeAgentLinkRequest(): NativeAgentLinkRequest =
    NativeAgentLinkRequest(
      platformPacksRoot = Path.of(platformPacks),
      skillsRoot = skills?.let(Path::of),
      home = inputs.userHome,
      environment = inputs.environment,
      selectedPlatforms = platforms.ifEmpty { null },
    )
}

@Inject
class InstallUnlinkClaudeAgentsCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val installNativeAgentLinkPort: InstallNativeAgentLinkPort,
) : DocumentedCliCommand("unlink-claude-agents", "Remove Claude native subagent markdown symlinks.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation(inputs, "unlink-claude-agents")) {
      return
    }
    val removed =
      installNativeAgentLinkPort.unlinkNativeAgents(
        InstallNativeAgentLinkOperationRequest(
          provider = NativeAgentLinkProvider.CLAUDE,
          linkRequest =
            NativeAgentLinkRequest(
              platformPacksRoot = Path.of(platformPacks),
              skillsRoot = skills?.let(Path::of),
              home = inputs.userHome,
              environment = inputs.environment,
              selectedPlatforms = platforms.ifEmpty { null },
            ),
        ),
      ).unlinked
    state.completeText(removed.joinToString("\n"), mapOf("removed" to removed.map(Path::toString)))
  }
}

@Inject
class InstallLinkCodexAgentsCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val installNativeAgentLinkPort: InstallNativeAgentLinkPort,
) : DocumentedCliCommand("link-codex-agents", "Render and link Codex native subagent TOMLs from source agents.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation(inputs, "link-codex-agents")) {
      return
    }
    completeNativeAgentLinkOutcome(
      state,
      installNativeAgentLinkPort.linkNativeAgents(
        InstallNativeAgentLinkOperationRequest(
          provider = NativeAgentLinkProvider.CODEX,
          linkRequest =
            NativeAgentLinkRequest(
              platformPacksRoot = Path.of(platformPacks),
              skillsRoot = skills?.let(Path::of),
              home = inputs.userHome,
              environment = inputs.environment,
              selectedPlatforms = platforms.ifEmpty { null },
            ),
        ),
      ).outcome,
    )
  }
}

@Inject
class InstallUnlinkCodexAgentsCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val installNativeAgentLinkPort: InstallNativeAgentLinkPort,
) : DocumentedCliCommand("unlink-codex-agents", "Remove Codex native subagent TOML symlinks from candidate dirs.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation(inputs, "unlink-codex-agents")) {
      return
    }
    val removed =
      installNativeAgentLinkPort.unlinkNativeAgents(
        InstallNativeAgentLinkOperationRequest(
          provider = NativeAgentLinkProvider.CODEX,
          linkRequest =
            NativeAgentLinkRequest(
              platformPacksRoot = Path.of(platformPacks),
              skillsRoot = skills?.let(Path::of),
              home = inputs.userHome,
              environment = inputs.environment,
              selectedPlatforms = platforms.ifEmpty { null },
            ),
        ),
      ).unlinked
    state.completeText(removed.joinToString("\n"), mapOf("removed" to removed.map(Path::toString)))
  }
}

@Inject
class InstallLinkCursorAgentsCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val installNativeAgentLinkPort: InstallNativeAgentLinkPort,
) : DocumentedCliCommand("link-cursor-agents", "Render and link Cursor native subagent markdown from source agents.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation(inputs, "link-cursor-agents")) {
      return
    }
    completeNativeAgentLinkOutcome(
      state,
      installNativeAgentLinkPort.linkNativeAgents(
        InstallNativeAgentLinkOperationRequest(
          provider = NativeAgentLinkProvider.CURSOR,
          linkRequest =
            NativeAgentLinkRequest(
              platformPacksRoot = Path.of(platformPacks),
              skillsRoot = skills?.let(Path::of),
              home = inputs.userHome,
              environment = inputs.environment,
              selectedPlatforms = platforms.ifEmpty { null },
            ),
        ),
      ).outcome,
    )
  }
}

@Inject
class InstallUnlinkCursorAgentsCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val installNativeAgentLinkPort: InstallNativeAgentLinkPort,
) : DocumentedCliCommand("unlink-cursor-agents", "Remove Cursor native subagent markdown symlinks.") {
  private val platformPacks by option("--platform-packs", help = "platform-packs root.").required()
  private val skills by option("--skills", help = "skills root.")
  private val platforms by option("--platform", help = "Selected platform slug to include.").multiple()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation(inputs, "unlink-cursor-agents")) {
      return
    }
    val removed =
      installNativeAgentLinkPort.unlinkNativeAgents(
        InstallNativeAgentLinkOperationRequest(
          provider = NativeAgentLinkProvider.CURSOR,
          linkRequest =
            NativeAgentLinkRequest(
              platformPacksRoot = Path.of(platformPacks),
              skillsRoot = skills?.let(Path::of),
              home = inputs.userHome,
              environment = inputs.environment,
              selectedPlatforms = platforms.ifEmpty { null },
            ),
        ),
      ).unlinked
    state.completeText(removed.joinToString("\n"), mapOf("removed" to removed.map(Path::toString)))
  }
}
