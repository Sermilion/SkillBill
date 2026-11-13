package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.data.approval.ApprovalGeneration
import dev.skillbill.desktop.core.data.approval.SessionApprovalGenerations
import dev.skillbill.desktop.core.data.approval.toCard
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.CursorMcpDependency
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
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
  private val terminated = CompletableDeferred<Unit>()
  @OptIn(ExperimentalCoroutinesApi::class)
  val termination: SelectClause1<Unit> get() = terminated.onAwait
  val isStopped: Boolean get() = stopping.get()

  fun admit(value: SessionApprovalGenerations): Boolean {
    generations.set(value)
    return value.admit(this)
  }

  override fun deliver(settled: ApprovalRequestRecord): ApprovalDeliveryState = protocol.deliver(settled)

  override fun stop(reason: String) {
    if (!stopping.compareAndSet(false, true)) return
    try {
      protocol.stop(reason)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      diagnostics.record("chat.cursor_protocol_stop_failed")
    } finally {
      terminated.complete(Unit)
      try {
        stopProcess()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        diagnostics.record("chat.cursor_process_stop_failed")
      }
    }
  }

  fun release() {
    if (!released.compareAndSet(false, true)) return
    try {
      generations.getAndSet(null)?.remove(this)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      diagnostics.record("chat.cursor_generation_release_failed")
    } finally {
      try {
        protocol.close()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        diagnostics.record("chat.cursor_protocol_release_failed")
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
): Pair<ChatCommand, CursorAcpLaunch> {
  val cursor = provider == AcpProvider.Cursor
  val command = ChatCommand(acpCommand(provider, shell, request.agent.model), request.scope.repositoryRoot, environment, "")
  return command to CursorAcpLaunch(
    cwd = request.scope.repositoryRoot,
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
): Flow<ChatEvent> = channelFlow {
  val cursor = provider == AcpProvider.Cursor
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
      "${provider.name} selects exact model variants and takes no separate effort; choose a model variant instead. The chat was not started."))
    return@channelFlow
  }
  val inputs = Channel<ChatProcessInput>(Channel.UNLIMITED) { it.written.complete(false) }
  val outputs = Channel<ChatProcessOutput>(Channel.UNLIMITED)
  val events = Channel<ChatEvent>(Channel.UNLIMITED)
  val readerRef = AtomicReference<kotlinx.coroutines.Job?>()
  val (command, launch) = acpTurnLaunch(provider, shell, environment, request, saved, prompt, dependency)
  // ACP streams text chunks without message ids, so a text run after a tool call starts a new message.
  var textOpen = false
  val protocol = CursorAcpSession(
    launch,
    Clock.systemUTC(),
    writeLine = { inputs.writeAndAwaitBlocking(it) },
    saveSession = {
      if (binding == null) {
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
      request.scope.repositoryRoot,
      provider.id,
      request.mode,
      environment[AgentHomeEnvironmentKeys.CURSOR_CONFIG_DIR].takeIf { cursor } ?: environment["HOME"].orEmpty(),
    ),
    protocol,
  ) { inputs.close(); readerRef.get()?.cancel() }
  if (opening is CursorChatApprovalOpening.Refused) {
    send(ChatEvent.Failed(ChatFailure.ApprovalUnsupported, opening.reason))
    return@channelFlow
  }
  val generation = (opening as CursorChatApprovalOpening.Opened).generation
  val reader = launch(start = CoroutineStart.LAZY) {
    try { live.openAcknowledged(command, inputs).collect { outputs.send(it) } }
    finally { outputs.close() }
  }
  readerRef.set(reader)
  val reply = ChatReplyStream(diagnostics)
  val eventJob = launch { for (event in events) reply.accept(event).forEach { send(it) } }
  if (!generation.isStopped) reader.start()
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
    protocol.close()
    val outcome = protocol.outcome()
    events.send(if (outcome?.failure == null && outcome != null) ChatEvent.Completed else
      ChatEvent.Failed(ChatFailure.ProcessFailed, outcome?.failure ?: "${provider.name} disconnected before completion."))
  } catch (_: TimeoutCancellationException) {
    protocol.stop("${provider.name} did not complete ACP setup in time; no prompt was sent.")
    events.send(ChatEvent.Failed(ChatFailure.ProcessFailed, protocol.outcome()?.failure))
  } catch (cancelled: CancellationException) {
    throw cancelled
  } finally {
    withContext(NonCancellable) {
      try {
        generation.release()
      } finally {
        inputs.close()
        try {
          reader.cancelAndJoin()
        } finally {
          outputs.cancel()
          events.close()
          eventJob.join()
        }
      }
    }
  }
}
