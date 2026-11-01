package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.session.SelectedAgentHomes
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps one `claude -p` process per chat session in the execution's worktree, with permission checks skipped as executions
 * run, and writes each turn to it as a stream-json message. The process outlives the turn, so a background command the agent
 * started keeps running, and the reply the agent starts when it finishes reaches [unprompted]. A turn cancelled before its
 * reply ends stops the process; [end] stops it between turns. A resumed session runs with the config directory it came from,
 * else the one the settings select.
 */
@Inject
@SingleIn(AppScope::class)
class ClaudeChatTransport(
  private val host: HostEnvironment,
  private val settings: AppSettingsRepository,
  private val launcher: LiveChatProcessLauncher,
  private val diagnostics: Diagnostics,
  appScope: ApplicationCoroutineScope,
) : ChatTransport {
  override val agentId = "claude"

  private val scope = appScope.scope
  private val sessions = ConcurrentHashMap<String, ClaudeLiveSession>()
  private val unpromptedEvents = MutableSharedFlow<Pair<String, ChatEvent>>(extraBufferCapacity = UNPROMPTED_BUFFER)

  override fun run(request: ChatTurnRequest): Flow<ChatEvent> = flow {
    val homes = SelectedAgentHomes(request.agent.profile ?: settings.settings.first().claudeConfigDir, host.userHome).current()
    val command = ChatCommand(
      argv = agentCommand("claude", claudeArguments(request), host.variables["SHELL"]),
      workingDirectory = request.scope.repositoryRoot,
      environment = claudeEnvironment(host.variables, homes),
      stdin = "",
    )
    val uuid = UUID.randomUUID().toString()
    val sink = Channel<ChatEvent>(Channel.UNLIMITED)
    val session = registered(request.sessionId, command, uuid, sink)
    session.send(uuid, claudeMessage(request))
    var answered = false
    try {
      for (event in sink) {
        emit(event)
        if (event is ChatEvent.Completed || event is ChatEvent.Failed) answered = true
      }
    } finally {
      if (!answered) stop(request.sessionId, session)
    }
  }

  override fun unprompted(sessionId: String): Flow<ChatEvent> =
    unpromptedEvents.filter { it.first == sessionId }.map { it.second }

  override fun end(sessionId: String) {
    sessions[sessionId]?.let { stop(sessionId, it) }
  }

  private fun registered(sessionId: String, command: ChatCommand, uuid: String, sink: Channel<ChatEvent>): ClaudeLiveSession {
    while (true) {
      var created: ClaudeLiveSession? = null
      val session = sessions.computeIfAbsent(sessionId) { open(sessionId, command).also { created = it } }
      created?.job?.start()
      if (session.register(uuid, sink)) return session
      sessions.remove(sessionId, session)
    }
  }

  private fun open(sessionId: String, command: ChatCommand): ClaudeLiveSession {
    val session = ClaudeLiveSession(diagnostics) { event -> unpromptedEvents.tryEmit(sessionId to event) }
    session.job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
      try {
        launcher.open(command, session.input).collect(session::accept)
      } finally {
        sessions.remove(sessionId, session)
        session.abandon()
      }
    }
    return session
  }

  private fun stop(sessionId: String, session: ClaudeLiveSession) {
    sessions.remove(sessionId, session)
    session.job.cancel()
  }

  private companion object {
    const val UNPROMPTED_BUFFER = 1024
  }
}

/** A later turn resumes the session in a new process only when the one that held it ended. */
internal fun claudeArguments(request: ChatTurnRequest): List<String> = buildList {
  addAll(listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose"))
  addAll(listOf("--include-partial-messages", "--replay-user-messages"))
  request.agent.model?.let { addAll(listOf("--model", it)) }
  request.agent.effort?.let { addAll(listOf("--effort", it)) }
  addAll(listOf(if (request.firstTurn) "--session-id" else "--resume", request.sessionId))
  add("--dangerously-skip-permissions")
  addAll(listOf("--system-prompt-snapshot", "off"))
  // The process outlives a mode change, so the mode travels with each message instead of the system prompt.
  addAll(listOf("--append-system-prompt", scopeSystemPrompt(request.scope)))
}

internal fun claudeMessage(request: ChatTurnRequest): String = listOfNotNull(modeNote(request.mode), request.prompt).joinToString("\n\n")

/** An empty `CLAUDE_CONFIG_DIR` means the default account, which only works with the variable unset. */
internal fun claudeEnvironment(variables: Map<String, String>, homes: Map<String, String>): Map<String, String> {
  val environment = variables + homes
  return if (homes[AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR]?.isEmpty() == true) {
    environment - AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR
  } else {
    environment
  }
}
