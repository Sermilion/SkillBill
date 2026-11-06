package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.data.approval.ApprovalGeneration
import dev.skillbill.desktop.core.data.approval.SessionApprovalGenerations
import dev.skillbill.desktop.core.data.approval.toCard
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import skillbill.approval.model.ApprovalExecutionMode
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.infrastructure.launcher.process.cursor.CursorAcpEvent
import skillbill.infrastructure.launcher.process.cursor.CursorAcpImage
import skillbill.infrastructure.launcher.process.cursor.CursorAcpLaunch
import skillbill.infrastructure.launcher.process.cursor.CursorAcpSession
import skillbill.ports.approval.model.ApprovalOwnerOpenRequest
import java.nio.file.Path
import java.time.Clock
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

internal sealed interface CursorChatApprovalOpening {
  data class Opened(val generation: CursorChatGeneration) : CursorChatApprovalOpening
  data class Refused(val reason: String) : CursorChatApprovalOpening
}

internal class CursorChatGeneration(
  private val protocol: CursorAcpSession,
  private val stopProcess: () -> Unit,
) : ApprovalGeneration {
  private val generations = AtomicReference<SessionApprovalGenerations?>()
  fun admit(value: SessionApprovalGenerations): Boolean { generations.set(value); return value.admit(this) }
  fun release() { generations.getAndSet(null)?.remove(this); protocol.close() }
  override fun end() { release(); stopProcess() }
}

internal fun cursorAcpTurn(
  live: LiveChatProcessLauncher,
  probes: ChatProcessLauncher,
  authority: ChatApprovalAuthority,
  store: ChatStore,
  diagnostics: Diagnostics,
  shell: String?,
  environment: Map<String, String>,
  request: ChatTurnRequest,
  saved: String?,
  prompt: String,
): Flow<ChatEvent> = channelFlow {
  val inputs = Channel<ChatProcessInput>(Channel.UNLIMITED) { it.written.complete(false) }
  val outputs = Channel<ChatProcessOutput>(Channel.UNLIMITED)
  val events = Channel<ChatEvent>(Channel.UNLIMITED)
  val readerRef = AtomicReference<kotlinx.coroutines.Job?>()
  var executable: String? = null
  val aliases = skillbill.install.model.AGENT_LAUNCHER_CLIS[skillbill.install.model.SupportedAgent.CURSOR]
    ?.executables.orEmpty()
  for (alias in aliases) {
    val probe = ChatCommand(agentCommand(alias, listOf("acp", "--help"), shell),
      request.scope.repositoryRoot, environment, "")
    var usable = false
    try {
      withTimeout(30_000) {
        probes.run(probe).collect { if (it is ChatProcessOutput.Exited && it.code == 0) usable = true }
      }
    } catch (_: TimeoutCancellationException) { diagnostics.record("chat.cursor_acp_probe_timeout") }
    if (usable) { executable = alias; break }
    diagnostics.record("chat.cursor_acp_alias_unavailable")
  }
  if (executable == null) {
    send(ChatEvent.Failed(ChatFailure.ApprovalUnsupported,
      "No declared Cursor executable supplies ACP stdio. The chat was not started."))
    return@channelFlow
  }
  val protocol = CursorAcpSession(
    CursorAcpLaunch(
      cwd = request.scope.repositoryRoot,
      prompt = prompt,
      model = request.agent.model?.let { model ->
        request.agent.effort?.takeIf { "[effort=" !in model }?.let { "$model[effort=$it]" } ?: model
      },
      mode = if (request.mode == ChatPermissionMode.ReadOnly) "plan" else "agent",
      savedSession = saved,
      images = request.images.map { CursorAcpImage(Base64.getEncoder().encodeToString(it.bytes), it.mediaType) },
    ),
    Clock.systemUTC(),
    writeLine = { inputs.writeAndAwaitBlocking(it) },
    saveSession = { store.saveAgentSession(request.sessionId, it) },
    onEvent = { event ->
      when (event) {
        is CursorAcpEvent.Text -> events.trySend(ChatEvent.TextDelta(event.text))
        is CursorAcpEvent.Tool -> {
          val tool = event.update
          val id = tool.string("toolCallId")
          if (id != null) {
            events.trySend(ChatEvent.ToolUse(ChatToolCall(id, tool.string("title") ?: "Cursor tool", tool.toString(), null)))
            if (tool.string("status") in setOf("completed", "failed")) {
              events.trySend(ChatEvent.ToolResult(id, tool.toString(), tool.string("status") == "failed"))
            }
          }
        }
        is CursorAcpEvent.Unsupported -> events.trySend(ChatEvent.TextDelta(
          "\nUnsupported Cursor interaction ${event.method}:\n${event.payload}\n"))
      }
    },
    onCard = { events.trySend(ChatEvent.ApprovalRequested(it.toCard())) },
    record = { diagnostics.record("chat.cursor_acp_diagnostic") },
  )
  val opening = authority.openCursor(ApprovalOwnerOpenRequest(
    checkoutRoot = Path.of(request.scope.repositoryRoot),
    runId = request.sessionId,
    provider = "cursor",
    executionMode = if (request.mode == ChatPermissionMode.ReadOnly) ApprovalExecutionMode.READ_ONLY else ApprovalExecutionMode.ACT,
    profileIdentity = environment[AgentHomeEnvironmentKeys.CURSOR_CONFIG_DIR] ?: environment["HOME"].orEmpty(),
    enclosingDeadline = null,
  ), protocol) { inputs.close(); readerRef.get()?.cancel() }
  if (opening is CursorChatApprovalOpening.Refused) {
    send(ChatEvent.Failed(ChatFailure.ApprovalUnsupported, opening.reason))
    return@channelFlow
  }
  val generation = (opening as CursorChatApprovalOpening.Opened).generation
  val command = ChatCommand(agentCommand(executable, listOf("acp"), shell), request.scope.repositoryRoot, environment, "")
  val reader = launch {
    try { live.openAcknowledged(command, inputs).collect { outputs.send(it) } }
    finally { outputs.close() }
  }
  readerRef.set(reader)
  val reply = ChatReplyStream(diagnostics)
  val eventJob = launch { for (event in events) reply.accept(event).forEach { send(it) } }
  fun accept(output: ChatProcessOutput) {
    when (output) {
      is ChatProcessOutput.Line -> protocol.observe(output.text)
      is ChatProcessOutput.Exited -> protocol.stop("Cursor exited before prompt completion (${output.code}): ${output.stderrTail}")
      is ChatProcessOutput.StartFailed -> protocol.stop(output.message)
    }
  }
  try {
    protocol.start()
    withTimeout(30_000) {
      while (!protocol.ready && protocol.outcome() == null) {
        val output = outputs.receiveCatching().getOrNull() ?: break
        accept(output)
      }
    }
    while (protocol.outcome() == null) {
      val output = outputs.receiveCatching().getOrNull() ?: break
      accept(output)
    }
    protocol.close()
    val outcome = protocol.outcome()
    events.send(if (outcome?.failure == null && outcome != null) ChatEvent.Completed else
      ChatEvent.Failed(ChatFailure.ProcessFailed, outcome?.failure ?: "Cursor disconnected before completion."))
  } catch (_: TimeoutCancellationException) {
    protocol.stop("Cursor did not complete ACP setup in time; no prompt was sent.")
    events.send(ChatEvent.Failed(ChatFailure.ProcessFailed, protocol.outcome()?.failure))
  } catch (cancelled: CancellationException) {
    throw cancelled
  } finally {
    generation.release()
    inputs.close()
    withContext(NonCancellable) { reader.cancelAndJoin() }
    outputs.cancel()
    events.close()
    withContext(NonCancellable) { eventJob.join() }
  }
}
