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
import skillbill.install.model.AGENT_LAUNCHER_CLIS
import skillbill.install.model.SupportedAgent
import skillbill.infrastructure.launcher.process.cursor.AcpProvider
import skillbill.infrastructure.launcher.process.cursor.CursorAcpEvent
import skillbill.infrastructure.launcher.process.cursor.CursorAcpImage
import skillbill.infrastructure.launcher.process.cursor.CursorAcpLaunch
import skillbill.infrastructure.launcher.process.cursor.CursorAcpMcpDependency
import skillbill.infrastructure.launcher.process.cursor.CursorAcpSession
import skillbill.infrastructure.launcher.process.cursor.commandCarriesModel
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

/** The provider's own launcher executables with its ACP arguments. */
internal fun acpCommand(provider: AcpProvider, shell: String?, model: String?): List<String> {
  val aliases = AGENT_LAUNCHER_CLIS[SupportedAgent.fromWire(provider.id)]?.executables.orEmpty()
  return agentCommand(aliases, provider.launchArguments(model), shell)
}

/** The process command and ACP launch for one turn; both take the model from the same request value. */
internal fun acpTurnLaunch(
  provider: AcpProvider,
  shell: String?,
  environment: Map<String, String>,
  request: ChatTurnRequest,
  saved: String?,
  prompt: String,
  dependency: CursorAcpMcpDependency,
  workingDirectory: String = request.scope.repositoryRoot,
): Pair<ChatCommand, CursorAcpLaunch> {
  val cursor = provider == AcpProvider.Cursor
  val command = ChatCommand(acpCommand(provider, shell, request.agent.model), workingDirectory, environment + provider.launchEnvironment, "")
  return command to CursorAcpLaunch(
    cwd = workingDirectory,
    prompt = prompt,
    model = request.agent.model?.let { model ->
      request.agent.effort?.takeIf { cursor && "[effort=" !in model }?.let { "$model[effort=$it]" } ?: model
    },
    mode = if (cursor) "agent" else null,
    savedSession = saved,
    images = request.images.map { CursorAcpImage(Base64.getEncoder().encodeToString(it.bytes), it.mediaType) },
    mcpDependency = dependency,
    provider = provider,
    modelOnCommandLine = commandCarriesModel(command.argv, request.agent.model),
  )
}

internal fun acpTurn(
  live: LiveChatProcessLauncher,
  authority: ChatApprovalAuthority,
  store: ChatStore,
  diagnostics: Diagnostics,
  launchFactory: (CursorAcpMcpDependency) -> Pair<ChatCommand, CursorAcpLaunch>,
  profileIdentity: String,
  request: ChatTurnRequest,
  saved: String?,
  binding: CursorSessionBinding?,
  provider: AcpProvider = AcpProvider.Cursor,
  openCodeBinding: OpenCodeSessionBinding? = null,
  expectedDatabaseSession: DatabaseSessionComponent? = null,
  stopRequested: CompletableDeferred<String>? = null,
  onDisposition: (ChatEvent) -> Unit = {},
  workingDirectory: String = openCodeBinding?.workingDirectory ?: request.scope.repositoryRoot,
  registerActive: (CursorAcpSession) -> Unit = {},
  clearActive: (CursorAcpSession) -> Unit = {},
  onSteeringResponse: (deliveryId: String, accepted: Boolean, detail: String?) -> Unit = { _, _, _ -> },
): Flow<ChatEvent> = channelFlow {
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
  if (provider.exactModels && request.agent.effort != null) {
    diagnostics.record("chat.${provider.seam}_effort_refused")
    send(ChatEvent.Failed(ChatFailure.AgentUnsupported,
      "${provider.name} requires a complete model UID. Remove the separate effort setting; the chat was not started."))
    return@channelFlow
  }
  val inputs = Channel<ChatProcessInput>(Channel.UNLIMITED) { it.written.complete(false) }
  val outputs = Channel<ChatProcessOutput>(Channel.UNLIMITED)
  val processCleanup = CompletableDeferred<ChatProcessCleanup>()
  val events = Channel<ChatEvent>(Channel.UNLIMITED)
  val readerRef = AtomicReference<kotlinx.coroutines.Job?>()
  val readerFailure = AtomicReference<String?>()
  val (command, launch) = launchFactory(dependency)
  // ACP streams text chunks without message ids, so a text run after a tool call starts a new message.
  var textOpen = false
  val protocol = CursorAcpSession(
    launch,
    Clock.systemUTC(),
    writeLine = { inputs.writeAndAwaitBlocking(it) },
    saveSession = {
      if (provider == AcpProvider.OpenCode && openCodeBinding != null) {
        store.saveOpenCodeSession(request.sessionId, it, openCodeBinding, request.savedHistory)
      } else if (provider == AcpProvider.Devin) {
        request.savedHistory?.let { history -> store.saveDevinSession(request.sessionId, it, history) } ?: false
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
    onSteeringResponse = onSteeringResponse,
  )
  val opening = authority.openCursor(
    chatOpenRequest(
      request.sessionId,
      workingDirectory,
      provider.id,
      request.mode,
      profileIdentity,
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
    launch(Dispatchers.IO) { generation.stop(requested.await()) }
  }
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
          generation.stop(providerProcessFailure(provider, reason, output.stderrTail))
        }
      }
      is ChatProcessOutput.Exited -> generation.stop(providerProcessFailure(
        provider, "${provider.name} exited before prompt completion (${output.code}).", output.stderrTail,
      ))
      is ChatProcessOutput.StartFailed -> generation.stop(providerProcessFailure(provider, output.message, ""))
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
    registerActive(protocol)
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
      clearActive(protocol)
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

private fun providerProcessFailure(provider: AcpProvider, primary: String, detail: String): String {
  val recovery = "Install ${provider.name} and make its CLI available on PATH, then retry."
  val observed = redactProviderDetail(detail)
  return listOf(redactProviderDetail(primary), recovery, observed.takeIf(String::isNotBlank)).filterNotNull().joinToString(" ")
}

private fun redactProviderDetail(value: String): String =
  value.trim().takeLast(1_200).replace(
    Regex("(?i)(token|password|secret|api[_-]?key)(\\s*[:=]\\s*)[^\\s,;]+"),
    "\$1\$2[redacted]",
  )
