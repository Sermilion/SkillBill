package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.session.SelectedAgentHomes
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatStoreRead
import dev.skillbill.desktop.core.domain.model.ChatTitleUpdate
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.infrastructure.launcher.process.cursor.AcpProvider
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/** Relative to the user's home; summary calls run here so no agent ever starts in a repository. */
internal const val CHAT_SUMMARY_PATH = ".skill-bill/desktop/chat-summaries"

/** An empty config home inside [CHAT_SUMMARY_PATH]: cursor label calls get no MCP servers or workspace state. */
internal const val CURSOR_LABEL_HOME = "cursor-label-config"

/** OpenCode's configured default model does not answer one-shot prompts, so its labels pin the Anthropic small model. */
internal const val OPENCODE_LABEL_MODEL = "anthropic/claude-haiku-4-5"

private const val STARTUP_LIMIT = 20
private const val UPDATES_BUFFER = 16
private val SUMMARY_TIMEOUT = 120.seconds

private enum class ChatLabelKind { Summary, Title }

private data class ChatLabelRequest(val sessionId: String, val kind: ChatLabelKind)

/**
 * Writes a short descriptive summary into stale saved chats and a 2-3 word title into chats that lack one, one call at a
 * time per kind, from a one-shot call to the chat's own agent on its default model, billed to the chat's account. Titles
 * drain on their own worker so a stale-summary backlog or a hung summary call cannot starve them. The queues run in the
 * application scope, so a label outlives the window that asked for it. [updates] emits the session id of each summary
 * written; [titleUpdates] emits each chat title written.
 */
@Inject
@SingleIn(AppScope::class)
class ChatSummarizer(
  private val host: HostEnvironment,
  private val settings: AppSettingsRepository,
  private val launcher: ChatProcessLauncher,
  private val protocolLauncher: BoundedProtocolLauncher,
  private val store: ChatStore,
  private val diagnostics: Diagnostics,
  private val appScope: ApplicationCoroutineScope,
) {
  /** Read once when the worker starts; tests swap in their own dispatcher. */
  internal var dispatcher: CoroutineDispatcher = Dispatchers.IO

  private val queue = Channel<ChatLabelRequest>(Channel.UNLIMITED)
  private val titleQueue = Channel<ChatLabelRequest>(Channel.UNLIMITED)
  private val pending: MutableSet<ChatLabelRequest> = ConcurrentHashMap.newKeySet()
  private val started = AtomicBoolean(false)
  private val updatesState = MutableSharedFlow<String>(extraBufferCapacity = UPDATES_BUFFER)
  private val titleUpdatesState = MutableSharedFlow<ChatTitleUpdate>(extraBufferCapacity = UPDATES_BUFFER)
  private val worker: Job by lazy { appScope.scope.launch(dispatcher) { work(queue) } }
  private val titleWorker: Job by lazy { appScope.scope.launch(dispatcher) { work(titleQueue) } }

  val updates: SharedFlow<String> = updatesState.asSharedFlow()

  /** A written chat title: its session id and its text. */
  val titleUpdates: SharedFlow<ChatTitleUpdate> = titleUpdatesState.asSharedFlow()

  /** Queues [sessionId] for a Recent chats summary unless it is already queued or running; returns at once and never throws. */
  fun request(sessionId: String) = enqueue(sessionId, ChatLabelKind.Summary)

  /** Queues [sessionId] for a generated chat title; free once the record holds one. */
  fun requestTitle(sessionId: String) = enqueue(sessionId, ChatLabelKind.Title)

  private fun enqueue(sessionId: String, kind: ChatLabelKind) {
    val request = ChatLabelRequest(sessionId, kind)
    if (!pending.add(request)) return
    when (kind) {
      ChatLabelKind.Title -> {
        titleWorker.start()
        titleQueue.trySend(request)
      }
      ChatLabelKind.Summary -> {
        worker.start()
        queue.trySend(request)
      }
    }
  }

  /** The first call queues the chats a quit or crash left stale, newest first; later calls do nothing. */
  fun start() {
    if (!started.compareAndSet(false, true)) return
    appScope.scope.launch(dispatcher) {
      val stale = store.staleSummariesRead(STARTUP_LIMIT)
      stale.failures.forEach { diagnostics.record("chat.summary_source_unreadable") }
      stale.entries.forEach { request(it.sessionId) }
    }
  }

  private suspend fun work(queue: ReceiveChannel<ChatLabelRequest>) {
    for (request in queue) {
      try {
        val read = when (request.kind) {
          ChatLabelKind.Summary -> store.staleForSummaryRead(request.sessionId)
          ChatLabelKind.Title -> store.missingTitleRead(request.sessionId)
        }
        when (read) {
          is ChatStoreRead.Failed -> diagnostics.record("chat.summary_source_unreadable")
          ChatStoreRead.Missing -> Unit
          is ChatStoreRead.Value -> read.value?.let { label(it, request.kind) }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        diagnostics.record("chat.summary_failed")
      } finally {
        pending.remove(request)
      }
    }
  }

  private suspend fun label(entry: ChatHistoryEntry, kind: ChatLabelKind) {
    val agentId = entry.agent.agentId
    val directory = File(host.userHome, CHAT_SUMMARY_PATH)
    val prompt = if (kind == ChatLabelKind.Title) titlePrompt(entry.lines) else summaryPrompt(entry.lines)
    val reply = if (agentId == "opencode") {
      // `opencode run` ends stdout before the reply arrives, so labels go through one ACP turn instead.
      directory.mkdirs()
      withTimeoutOrNull(SUMMARY_TIMEOUT) {
        openCodeLabelReply(
          protocolLauncher,
          ChatCommand(
            argv = acpCommand(AcpProvider.OpenCode, host.variables["SHELL"], null),
            workingDirectory = directory.path,
            environment = host.variables +
              entry.agent.profile?.let { mapOf(AgentHomeEnvironmentKeys.OPENCODE_CONFIG_DIR to it) }.orEmpty() +
              AcpProvider.OpenCode.launchEnvironment,
            stdin = "",
          ),
          entry.agent.model?.takeIf(String::isNotBlank) ?: OPENCODE_LABEL_MODEL,
          prompt,
          diagnostics,
        )
      }
    } else {
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
      events?.takeIf { all -> all.none { it is ChatEvent.Failed } }
        ?.filterIsInstance<ChatEvent.TextDelta>()?.joinToString("") { it.text }
    }
    val title = reply?.let(::summaryTitle)
    if (title == null) {
      diagnostics.record("chat.summary_failed")
      return
    }
    val saved = when (kind) {
      // The snapshot's updatedAt, so a turn saved during the call leaves the chat stale for the next trigger.
      ChatLabelKind.Summary -> store.saveSummary(entry.sessionId, title, entry.updatedAt)
      ChatLabelKind.Title -> store.saveTitle(entry.sessionId, title)
    }
    if (saved) when (kind) {
      ChatLabelKind.Summary -> updatesState.tryEmit(entry.sessionId)
      ChatLabelKind.Title -> titleUpdatesState.tryEmit(ChatTitleUpdate(entry.sessionId, title))
    }
  }

  private fun binary(agentId: String): String = if (agentId == "cursor") "agent" else agentId

  /** The environment and config home each agent's chat transport builds for [profile]. */
  private suspend fun environment(agentId: String, profile: String?): Map<String, String> = when (agentId) {
    "claude" -> claudeEnvironment(
      host.variables,
      SelectedAgentHomes(profile ?: settings.settings.first().claudeConfigDir, host.userHome).current(),
    )
    "codex" -> host.variables + profile?.let { mapOf(AgentHomeEnvironmentKeys.CODEX_HOME to it) }.orEmpty()
    // A label call never needs MCP: an empty config home keeps the profile's MCP servers and workspace state out.
    "cursor" -> host.variables + mapOf(
      AgentHomeEnvironmentKeys.CURSOR_CONFIG_DIR to
        File(File(host.userHome, CHAT_SUMMARY_PATH), CURSOR_LABEL_HOME).also(File::mkdirs).path,
    )
    else -> host.variables + profile?.let { mapOf(AgentHomeEnvironmentKeys.CURSOR_CONFIG_DIR to it) }.orEmpty()
  }
}
