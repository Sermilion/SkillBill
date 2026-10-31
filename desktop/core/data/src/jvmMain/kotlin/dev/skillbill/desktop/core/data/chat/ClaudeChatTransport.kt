package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.session.SelectedAgentHomes
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Runs each turn as one `claude -p` process in the execution's worktree with permission checks skipped, as executions run. A
 * resumed session runs with the config directory it came from, else the one the settings select.
 */
@Inject
@SingleIn(AppScope::class)
class ClaudeChatTransport(
  private val host: HostEnvironment,
  private val settings: AppSettingsRepository,
  private val launcher: ChatProcessLauncher,
  private val diagnostics: Diagnostics,
) : ChatTransport {
  override val agentId = "claude"

  override fun run(request: ChatTurnRequest): Flow<ChatEvent> = flow {
    val homes = SelectedAgentHomes(request.agent.profile ?: settings.settings.first().claudeConfigDir, host.userHome).current()
    val command = ChatCommand(
      argv = agentCommand("claude", claudeArguments(request), host.variables["SHELL"]),
      workingDirectory = request.scope.repositoryRoot,
      environment = claudeEnvironment(host.variables, homes),
      stdin = request.prompt,
    )
    emitAll(runChatTurn(launcher, diagnostics, command, ClaudeDecoder))
  }

  private object ClaudeDecoder : ChatLineDecoder {
    override fun decode(line: String): List<ChatEvent> = decodeClaudeLine(line)
  }
}

internal fun claudeArguments(request: ChatTurnRequest): List<String> = buildList {
  addAll(listOf("-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages"))
  request.agent.model?.let { addAll(listOf("--model", it)) }
  request.agent.effort?.let { addAll(listOf("--effort", it)) }
  addAll(listOf(if (request.firstTurn) "--session-id" else "--resume", request.sessionId))
  add("--dangerously-skip-permissions")
  // Each turn appends the prompt for its own mode, so a recorded snapshot would go stale.
  addAll(listOf("--system-prompt-snapshot", "off"))
  addAll(listOf("--append-system-prompt", scopeSystemPrompt(request.scope, request.mode)))
}

/** An empty `CLAUDE_CONFIG_DIR` means the default account, which only works with the variable unset. */
internal fun claudeEnvironment(variables: Map<String, String>, homes: Map<String, String>): Map<String, String> {
  val environment = variables + homes
  return if (homes[AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR]?.isEmpty() == true) {
    environment - AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR
  } else {
    environment
  }
}
