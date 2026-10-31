package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import java.io.File

private const val COMMAND_NOT_FOUND = 127
private val posixShells = setOf("sh", "bash", "zsh", "dash", "ksh")

/** Reads one turn's stdout; a decoder lives for one turn, so it may keep state across lines. */
internal interface ChatLineDecoder {
  /** A JSON stream skips login-shell output printed before its first object; plain text keeps every line. */
  val json: Boolean get() = true

  fun decode(line: String): List<ChatEvent>

  /** The process ended without a terminal event; exit 0 counts as a failure unless the decoder says otherwise. */
  fun exited(code: Int, stderrTail: String): List<ChatEvent> = listOf(exitFailure(code, stderrTail))
}

/** The login shell's exec exits 127 when the agent binary is not on its PATH. */
internal fun exitFailure(code: Int, detail: String): ChatEvent.Failed =
  ChatEvent.Failed(if (code == COMMAND_NOT_FOUND) ChatFailure.BinaryMissing else ChatFailure.ProcessFailed, detail.takeLast(2000))

/** Runs [command] and streams its decoded events through [ChatReplyStream] until the first terminal event. */
internal fun runChatTurn(
  launcher: ChatProcessLauncher,
  diagnostics: Diagnostics,
  command: ChatCommand,
  decoder: ChatLineDecoder,
): Flow<ChatEvent> = flow {
  val reply = ChatReplyStream(diagnostics)
  var streamStarted = !decoder.json
  launcher.run(command).transformWhile { output ->
    // The login shell's profile can print to stdout before the agent starts; only lines after the first JSON object are the stream.
    if (output is ChatProcessOutput.Line && !streamStarted) {
      streamStarted = output.text.trimStart().startsWith("{")
      if (!streamStarted) {
        if (output.text.isNotBlank()) diagnostics.record("chat.login_output_skipped")
        return@transformWhile true
      }
    }
    val events = when (output) {
      is ChatProcessOutput.Line -> decoder.decode(output.text)
      // Agents start through a login shell, which reports a missing binary as exit 127; a start failure is a missing worktree or shell.
      is ChatProcessOutput.StartFailed -> listOf(ChatEvent.Failed(ChatFailure.ProcessFailed, output.message))
      is ChatProcessOutput.Exited -> decoder.exited(output.code, output.stderrTail)
    }
    for (event in events.flatMap(reply::accept)) {
      if (event is ChatEvent.Failed) diagnostics.record("chat.turn_failed")
      emit(event)
    }
    events.none { it is ChatEvent.Completed || it is ChatEvent.Failed }
  }.collect(::emit)
}

/** Runs [binary] through a POSIX login shell so the login PATH applies; fish and other shells read `$0`/`$@` differently, so they use `/bin/sh`. */
internal fun agentCommand(binary: String, arguments: List<String>, shell: String?): List<String> {
  if (shell.isNullOrBlank()) return listOf(binary) + arguments
  val loginShell = if (File(shell).name in posixShells) shell else "/bin/sh"
  return listOf(loginShell, "-l", "-c", "exec \"\$0\" \"\$@\"", binary) + arguments
}

/** Agents whose CLI has no system-prompt flag get the scope ahead of the first message. */
internal fun withScope(scope: String, prompt: String): String = "$scope\n\n---\n\n$prompt"
