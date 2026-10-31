package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.session.SelectedAgentHomes
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.File

private const val COMMAND_NOT_FOUND = 127
private val posixShells = setOf("sh", "bash", "zsh", "dash", "ksh")

/** Runs each turn as one `claude -p` process in the execution's worktree, limited by the turn's [permissionProfile]. */
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
    val homes = SelectedAgentHomes(settings.settings.first().claudeConfigDir, host.userHome).current()
    val command = ChatCommand(
      argv = claudeCommand(claudeArguments(request), host.variables["SHELL"]),
      workingDirectory = request.scope.repositoryRoot,
      environment = claudeEnvironment(host.variables, homes),
      stdin = request.prompt,
    )
    val reply = ChatReplyStream(diagnostics)
    var streamStarted = false
    launcher.run(command).transformWhile { output ->
      // The login shell's profile can print to stdout before claude starts; only lines after the first JSON object are the stream.
      if (output is ChatProcessOutput.Line && !streamStarted) {
        streamStarted = output.text.trimStart().startsWith("{")
        if (!streamStarted) {
          if (output.text.isNotBlank()) diagnostics.record("chat.login_output_skipped")
          return@transformWhile true
        }
      }
      val events = when (output) {
        is ChatProcessOutput.Line -> decodeClaudeLine(output.text)
        is ChatProcessOutput.StartFailed -> listOf(ChatEvent.Failed(ChatFailure.BinaryMissing, output.message))
        // Exit without a result line, including exit 0; the login shell's exec exits 127 when claude is not on its PATH.
        is ChatProcessOutput.Exited -> {
          val failure = if (output.code == COMMAND_NOT_FOUND) ChatFailure.BinaryMissing else ChatFailure.ProcessFailed
          listOf(ChatEvent.Failed(failure, output.stderrTail.takeLast(2000)))
        }
      }
      for (event in events.flatMap(reply::accept)) {
        if (event is ChatEvent.Failed) diagnostics.record("chat.turn_failed")
        emit(event)
      }
      events.none { it is ChatEvent.Completed || it is ChatEvent.Failed }
    }.collect(::emit)
  }
}

internal fun claudeArguments(request: ChatTurnRequest): List<String> {
  val profile = permissionProfile(request.mode)
  return buildList {
    addAll(listOf("-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages"))
    request.agent.model?.let { addAll(listOf("--model", it)) }
    request.agent.effort?.let { addAll(listOf("--effort", it)) }
    addAll(listOf(if (request.firstTurn) "--session-id" else "--resume", request.sessionId))
    add("--restricted")
    addAll(listOf("--tools", profile.tools))
    addAll(listOf("--permission-mode", "dontAsk"))
    addAll(listOf("--permission-prompts", "none"))
    add("--allowedTools")
    addAll(profile.allowedTools)
    // Also ends the variadic --allowedTools list.
    addAll(listOf("--system-prompt-snapshot", "off"))
    addAll(listOf("--append-system-prompt", scopeSystemPrompt(request.scope, request.mode)))
  }
}

/** Runs through a POSIX login shell so the login PATH applies; fish and other shells read `$0`/`$@` differently, so they use `/bin/sh`. */
internal fun claudeCommand(arguments: List<String>, shell: String?): List<String> {
  if (shell.isNullOrBlank()) return listOf("claude") + arguments
  val loginShell = if (File(shell).name in posixShells) shell else "/bin/sh"
  return listOf(loginShell, "-l", "-c", "exec \"\$0\" \"\$@\"", "claude") + arguments
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
