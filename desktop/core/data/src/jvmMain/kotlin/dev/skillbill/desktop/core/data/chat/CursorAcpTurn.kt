package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.data.approval.ApprovalGeneration
import dev.skillbill.desktop.core.data.approval.SessionApprovalGenerations
import dev.skillbill.desktop.core.data.approval.toCard
import dev.skillbill.desktop.core.data.session.DatabaseSessionComponent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.CursorMcpDependency
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.SelectClause1
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.infrastructure.launcher.process.cursor.AcpProvider
import skillbill.infrastructure.launcher.process.cursor.CursorAcpEvent
import skillbill.infrastructure.launcher.process.cursor.CursorAcpImage
import skillbill.infrastructure.launcher.process.cursor.CursorAcpLaunch
import skillbill.infrastructure.launcher.process.cursor.CursorAcpMcpDependency
import skillbill.infrastructure.launcher.process.cursor.CursorAcpMcpServer
import skillbill.infrastructure.launcher.process.cursor.CursorAcpSession
import skillbill.infrastructure.launcher.process.cursor.cursorAcpDependencyRefusal
import skillbill.ports.approval.ApprovalOwnerEffects
import skillbill.ports.approval.model.ApprovalDeliveryState
import java.time.Clock
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal sealed interface CursorChatApprovalOpening {
  data class Opened(val generation: CursorChatGeneration) : CursorChatApprovalOpening
  data class Refused(val reason: String) : CursorChatApprovalOpening
}

internal class CursorChatGeneration(
  private val protocol: CursorAcpSession,
  private val stopProcess: () -> Unit,
  private val diagnostics: Diagnostics,
) : ApprovalGeneration, ApprovalOwnerEffects {
  private val generations = AtomicReference<SessionApprovalGenerations?>()
  private val stopping = AtomicBoolean(false)
  private val released = AtomicBoolean(false)
  private val cleanupFailures = java.util.Collections.synchronizedList(mutableListOf<String>())
  private val terminated = CompletableDeferred<Unit>()
  @OptIn(ExperimentalCoroutinesApi::class)
  val termination: SelectClause1<Unit> get() = terminated.onAwait
  val isStopped: Boolean get() = stopping.get()
  val cleanupFailure: String? get() = synchronized(cleanupFailures) { cleanupFailures.takeIf { it.isNotEmpty() }?.joinToString("; ") }

  fun admit(value: SessionApprovalGenerations): Boolean {
    generations.set(value)
    return value.admit(this)
  }

  override fun deliver(settled: ApprovalRequestRecord): ApprovalDeliveryState = protocol.deliver(settled)

  @Synchronized
  override fun stop(reason: String) {
    if (!stopping.compareAndSet(false, true)) return
    try {
      protocol.stop(reason)
    } catch (_: Exception) {
      diagnostics.record("chat.cursor_protocol_stop_failed")
      cleanupFailures += "ACP stop failed."
    } finally {
      try {
        stopProcess()
      } catch (_: Exception) {
        diagnostics.record("chat.cursor_process_stop_failed")
        cleanupFailures += "Process stop failed."
      }
      terminated.complete(Unit)
    }
  }

  @Synchronized
  fun release() {
    if (!released.compareAndSet(false, true)) return
    try {
      generations.getAndSet(null)?.remove(this)
    } catch (_: Exception) {
      diagnostics.record("chat.cursor_generation_release_failed")
      cleanupFailures += "Approval generation release failed."
    } finally {
      try {
        protocol.close()
      } catch (_: Exception) {
        diagnostics.record("chat.cursor_protocol_release_failed")
        cleanupFailures += "ACP release failed."
      }
    }
  }

  override fun end() {
    try {
      stop("Cursor approval owner ended while its ACP turn was active.")
    } finally {
      release()
    }
  }
}

internal fun cursorAcpTurn(
  live: LiveChatProcessLauncher,
  authority: ChatApprovalAuthority,
  store: ChatStore,
  diagnostics: Diagnostics,
  shell: String?,
  environment: Map<String, String>,
  request: ChatTurnRequest,
  saved: String?,
  binding: CursorSessionBinding?,
  prompt: String,
  provider: AcpProvider = AcpProvider.Cursor,
  openCodeBinding: OpenCodeSessionBinding? = null,
  mcpServers: List<CursorAcpMcpServer> = emptyList(),
  expectedDatabaseSession: DatabaseSessionComponent? = null,
  stopRequested: CompletableDeferred<String>? = null,
  onDisposition: (ChatEvent) -> Unit = {},
): Flow<ChatEvent> = channelFlow {
  val cursor = provider == AcpProvider.Cursor
  val workingDirectory = openCodeBinding?.workingDirectory ?: request.scope.repositoryRoot
  val dependency = when (request.cursorMcpDependency) {
    CursorMcpDependency.ProjectUserOnly -> CursorAcpMcpDependency.ProjectUserOnly
    CursorMcpDependency.TeamDashboardRequired -> CursorAcpMcpDependency.TeamDashboardRequired
  }
  if (provider.lacksTeamDashboardMcp) {
    cursorAcpDependencyRefusal(dependency)?.let { reason ->
      diagnostics.record("chat.cursor_mcp_dependency_refused")
      send(ChatEvent.Failed(ChatFailure.AgentUnsupported, reason))
      return@channelFlow
    }
  }
  val inputs = Channel<ChatProcessInput>(Channel.UNLIMITED) { it.written.complete(false) }
  val outputs = Channel<ChatProcessOutput>(Channel.UNLIMITED)
  val processCleanup = CompletableDeferred<ChatProcessCleanup>()
  val events = Channel<ChatEvent>(Channel.UNLIMITED)
  val readerRef = AtomicReference<kotlinx.coroutines.Job?>()
  val readerFailure = AtomicReference<String?>()
  val aliases = if (provider == AcpProvider.OpenCode) listOf("opencode") else skillbill.install.model.AGENT_LAUNCHER_CLIS[
    if (cursor) skillbill.install.model.SupportedAgent.CURSOR else skillbill.install.model.SupportedAgent.JUNIE,
  ]?.executables.orEmpty()
  // ACP streams text chunks without message ids, so a text run after a tool call starts a new message.
  var textOpen = false
  val protocol = CursorAcpSession(
    CursorAcpLaunch(
      cwd = workingDirectory,
      prompt = prompt,
      model = request.agent.model?.let { model ->
        request.agent.effort?.takeIf { cursor && "[effort=" !in model }?.let { "$model[effort=$it]" } ?: model
      },
      effort = request.agent.effort.takeIf { provider == AcpProvider.OpenCode },
      mode = when {
        cursor -> "agent"
        provider == AcpProvider.OpenCode -> if (request.mode == ChatPermissionMode.ReadOnly) "plan" else "build"
        else -> null
      },
      savedSession = saved,
      images = request.images.map { CursorAcpImage(Base64.getEncoder().encodeToString(it.bytes), it.mediaType) },
      mcpDependency = dependency,
      provider = provider,
      mcpServers = mcpServers,
    ),
    Clock.systemUTC(),
    writeLine = { inputs.writeAndAwaitBlocking(it) },
    saveSession = {
      if (provider == AcpProvider.OpenCode && openCodeBinding != null) {
        store.saveOpenCodeSession(request.sessionId, it, openCodeBinding, request.savedHistory)
      } else if (binding == null) {
        true
      } else if (saved == null) {
        store.saveCursorSession(request.sessionId, it, binding)
      } else {
        store.agentSession(request.sessionId) == it && store.cursorSessionBinding(request.sessionId) == binding
      }
    },
    onEvent = { event ->
      when (event) {
        is CursorAcpEvent.Text -> {
          if (!textOpen) events.trySend(ChatEvent.MessageStarted)
          textOpen = true
          events.trySend(ChatEvent.TextDelta(event.text))
        }
        is CursorAcpEvent.Tool -> {
          textOpen = false
          val tool = event.update
          val id = tool.string("toolCallId")
          if (id != null) {
            events.trySend(ChatEvent.ToolUse(ChatToolCall(id, tool.string("title") ?: "${provider.name} tool", tool.toString(), null)))
            if (tool.string("status") in setOf("completed", "failed")) {
              events.trySend(ChatEvent.ToolResult(id, tool.toString(), tool.string("status") == "failed"))
            }
          }
        }
        is CursorAcpEvent.Unsupported -> events.trySend(ChatEvent.TextDelta(
          "\nUnsupported ${provider.name} interaction ${event.method}:\n${event.payload}\n"))
      }
    },
    onCard = { events.trySend(ChatEvent.ApprovalRequested(it.toCard())) },
    record = { diagnostics.record("chat.${provider.seam}_diagnostic") },
  )
  val opening = authority.openCursor(
    chatOpenRequest(
      request.sessionId,
      workingDirectory,
      provider.id,
      request.mode,
      when {
        provider == AcpProvider.OpenCode -> openCodeBinding?.profileIdentity.orEmpty()
        cursor -> environment[AgentHomeEnvironmentKeys.CURSOR_CONFIG_DIR] ?: environment["HOME"].orEmpty()
        else -> environment["HOME"].orEmpty()
      },
    ),
    protocol,
    { inputs.close(); readerRef.get()?.cancel() },
    expectedDatabaseSession,
  )
  if (opening is CursorChatApprovalOpening.Refused) {
    send(ChatEvent.Failed(ChatFailure.ApprovalUnsupported, opening.reason))
    return@channelFlow
  }
  val generation = (opening as CursorChatApprovalOpening.Opened).generation
  val initialStop = stopRequested?.let { requested -> if (requested.isCompleted) requested.await() else null }
  if (initialStop != null) withContext(Dispatchers.IO) { generation.stop(initialStop) }
  val stopWatcher = stopRequested?.takeUnless { it.isCompleted }?.let { requested ->
    launch(Dispatchers.IO) {
      generation.stop(requested.await())
    }
  }
  val command = ChatCommand(agentCommand(aliases, provider.acpArguments, shell), workingDirectory, environment, "")
  val reader = launch(start = CoroutineStart.LAZY) {
    try {
      live.openAcknowledged(command, inputs, processCleanup).collect { outputs.send(it) }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      readerFailure.set(failure.message ?: failure.toString())
      diagnostics.record("chat.process_reader_failed")
    }
    finally { outputs.close() }
  }
  readerRef.set(reader)
  val reply = ChatReplyStream(diagnostics)
  val eventJob = launch { for (event in events) reply.accept(event).forEach { send(it) } }
  if (!generation.isStopped) reader.start() else processCleanup.complete(ChatProcessCleanup())
  fun accept(output: ChatProcessOutput) {
    when (output) {
      is ChatProcessOutput.Line -> protocol.observe(output.text)
      is ChatProcessOutput.StdoutEnded -> {
        if (protocol.outcome() == null) {
          val reason = output.readFailure?.let { "${provider.name} stdout read failed: $it" }
            ?: "${provider.name} stdout disconnected."
          generation.stop(listOf(reason, output.stderrTail).filter(String::isNotBlank).joinToString("\n"))
        }
      }
      is ChatProcessOutput.Exited -> generation.stop(
        "${provider.name} exited before prompt completion (${output.code}): ${output.stderrTail}",
      )
      is ChatProcessOutput.StartFailed -> generation.stop(output.message)
    }
  }
  @OptIn(ExperimentalCoroutinesApi::class)
  suspend fun receiveOutput(): ChatProcessOutput? = select {
    outputs.onReceiveCatching { it.getOrNull() }
    generation.termination { null }
  }
  var terminal: ChatEvent? = null
  var cancelled: CancellationException? = null
  try {
    if (!generation.isStopped) protocol.start()
    withTimeout(30_000) {
      while (!protocol.ready && protocol.outcome() == null) {
        val output = receiveOutput() ?: break
        accept(output)
      }
    }
    while (protocol.outcome() == null) {
      val output = receiveOutput() ?: break
      accept(output)
    }
    val outcome = protocol.outcome()
    terminal = if (outcome?.failure == null && outcome != null) ChatEvent.Completed else
      ChatEvent.Failed(ChatFailure.ProcessFailed, outcome?.failure ?: "${provider.name} disconnected before completion.")
  } catch (_: TimeoutCancellationException) {
    protocol.stop("${provider.name} did not complete ACP setup in time; no prompt was sent.")
    terminal = ChatEvent.Failed(ChatFailure.ProcessFailed, protocol.outcome()?.failure
      ?: "${provider.name} did not complete ACP setup in time; no prompt was sent.")
  } catch (failure: CancellationException) {
    cancelled = failure
  } finally {
    withContext(NonCancellable) {
      generation.release()
      inputs.close()
      reader.cancelAndJoin()
      if (!processCleanup.isCompleted) {
        processCleanup.complete(ChatProcessCleanup("The process reader ended without a cleanup report."))
      }
      outputs.cancel()
      val processDisposition = runCatching { processCleanup.await() }
        .getOrElse { ChatProcessCleanup("Process cleanup report was unavailable.") }
      val cleanupFailure = listOfNotNull(generation.cleanupFailure, processDisposition.failure, readerFailure.get())
        .filter(String::isNotBlank).joinToString("; ").ifBlank { null }
      if (cleanupFailure != null) diagnostics.record("chat.turn_cleanup_failed")
      if (cancelled != null) {
        diagnostics.record("chat.turn_cancelled_during_cleanup")
        val reason = listOfNotNull("${provider.name} turn was cancelled.", cleanupFailure?.let { "Cleanup failed: $it" })
          .joinToString(" ")
        onDisposition(ChatEvent.Failed(ChatFailure.ProcessFailed, reason))
      } else {
        val proposed = if (generation.isStopped && terminal !is ChatEvent.Failed) ChatEvent.Failed(
          ChatFailure.ProcessFailed,
          "${provider.name} turn was stopped before terminal settlement.",
        ) else terminal ?: ChatEvent.Failed(
          ChatFailure.ProcessFailed,
          "${provider.name} ended without a complete protocol outcome.",
        )
        terminal = when {
          proposed is ChatEvent.Completed && cleanupFailure != null ->
            ChatEvent.Failed(ChatFailure.ProcessFailed, "${provider.name} cleanup failed: $cleanupFailure")
          proposed is ChatEvent.Failed && cleanupFailure != null ->
            proposed.copy(detail = listOfNotNull(proposed.detail, "Cleanup failed: $cleanupFailure").joinToString(" "))
          else -> proposed
        }
        onDisposition(requireNotNull(terminal))
        events.send(requireNotNull(terminal))
      }
      stopWatcher?.cancelAndJoin()
      events.close()
      eventJob.join()
    }
  }
  cancelled?.let { throw it }
}
