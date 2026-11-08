package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.session.SelectedAgentHomes
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/** Relative to the user's home; summary calls run here so no agent ever starts in a repository. */
internal const val CHAT_SUMMARY_PATH = ".skill-bill/desktop/chat-summaries"

private const val STARTUP_LIMIT = 20
private const val UPDATES_BUFFER = 16
private val SUMMARY_TIMEOUT = 120.seconds

/**
 * Writes a short descriptive title into stale saved chats, one at a time app-wide, from a one-shot call to the chat's own agent
 * on its cheapest model, billed to the chat's account. The queue runs in the application scope, so a summary outlives the
 * window that asked for it. [updates] emits the session id of each summary written.
 */
@Inject
@SingleIn(AppScope::class)
class ChatSummarizer(
  private val host: HostEnvironment,
  private val settings: AppSettingsRepository,
  private val launcher: ChatProcessLauncher,
  private val store: ChatStore,
  private val diagnostics: Diagnostics,
  private val appScope: ApplicationCoroutineScope,
) {
  /** Read once when the worker starts; tests swap in their own dispatcher. */
  internal var dispatcher: CoroutineDispatcher = Dispatchers.IO

  private val queue = Channel<String>(Channel.UNLIMITED)
  private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()
  private val started = AtomicBoolean(false)
  private val updatesState = MutableSharedFlow<String>(extraBufferCapacity = UPDATES_BUFFER)
  private val worker: Job by lazy { appScope.scope.launch(dispatcher) { work() } }

  val updates: SharedFlow<String> = updatesState.asSharedFlow()

  /** Queues [sessionId] unless it is already queued or running; returns at once and never throws. */
  fun request(sessionId: String) {
    worker.start()
    if (pending.add(sessionId)) queue.trySend(sessionId)
  }

  /** The first call queues the chats a quit or crash left stale, newest first; later calls do nothing. */
  fun start() {
    if (!started.compareAndSet(false, true)) return
    appScope.scope.launch(dispatcher) {
      store.staleSummaries(STARTUP_LIMIT).forEach { request(it.sessionId) }
    }
  }

  private suspend fun work() {
    for (sessionId in queue) {
      try {
        store.staleForSummary(sessionId)?.let { summarize(it) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        diagnostics.record("chat.summary_failed")
      } finally {
        pending.remove(sessionId)
      }
    }
  }

  private suspend fun summarize(entry: ChatHistoryEntry) {
    val agentId = entry.agent.agentId
    val directory = File(host.userHome, CHAT_SUMMARY_PATH)
    val prompt = summaryPrompt(entry.lines)
    val arguments = summaryArguments(agentId, directory.path, prompt)
    if (arguments == null) {
      diagnostics.record("chat.summary_unsupported_agent")
      return
    }
    directory.mkdirs()
    val command = ChatCommand(
      argv = agentCommand(binary(agentId), arguments, host.variables["SHELL"]),
      workingDirectory = directory.path,
      environment = environment(agentId, entry.agent.profile),
      stdin = summaryStdin(agentId, prompt),
    )
    // Ending the collection early destroys the process, so the timeout also ends it.
    val events = withTimeoutOrNull(SUMMARY_TIMEOUT) { runChatTurn(launcher, diagnostics, command, summaryDecoder(agentId)).toList() }
    val title = events?.takeIf { all -> all.none { it is ChatEvent.Failed } }
      ?.filterIsInstance<ChatEvent.TextDelta>()?.joinToString("") { it.text }?.let(::summaryTitle)
    if (title == null) {
      diagnostics.record("chat.summary_failed")
      return
    }
    // The snapshot's updatedAt, so a turn saved during the call leaves the chat stale for the next trigger.
    if (store.saveSummary(entry.sessionId, title, entry.updatedAt)) updatesState.tryEmit(entry.sessionId)
  }

  private fun binary(agentId: String): String = if (agentId == "cursor") "agent" else agentId

  /** The environment and config home each agent's chat transport builds for [profile]. */
  private suspend fun environment(agentId: String, profile: String?): Map<String, String> = when (agentId) {
    "claude" -> claudeEnvironment(
      host.variables,
      SelectedAgentHomes(profile ?: settings.settings.first().claudeConfigDir, host.userHome).current(),
    )
    "codex" -> host.variables + profile?.let { mapOf(AgentHomeEnvironmentKeys.CODEX_HOME to it) }.orEmpty()
    else -> host.variables + profile?.let { mapOf(AgentHomeEnvironmentKeys.CURSOR_CONFIG_DIR to it) }.orEmpty()
  }
}
