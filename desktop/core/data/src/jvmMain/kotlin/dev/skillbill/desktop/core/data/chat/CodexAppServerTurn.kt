package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.domain.model.ChatSteeringResult
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.ApprovalCard
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import skillbill.infrastructure.launcher.process.codex.CodexAppServerProtocol
import skillbill.infrastructure.launcher.process.codex.CodexSteerResponse
import skillbill.infrastructure.launcher.process.codex.CodexThreadOpening

internal fun codexAppServerTurn(
  launcher: LiveChatProcessLauncher,
  diagnostics: Diagnostics,
  command: ChatCommand,
  request: ChatTurnRequest,
  savedThread: String?,
  prompt: String,
  imagePaths: List<String>,
  saveThread: (String) -> Boolean,
  registerActive: (CodexAppServerRpc, String, String) -> Unit,
  clearActive: (CodexAppServerRpc) -> Unit,
  releaseRpc: (CodexAppServerRpc) -> Unit,
  openApproval: (
    writeLine: (String) -> Boolean,
    stopProcess: () -> Unit,
    onCard: (ApprovalCard) -> Unit,
  ) -> CodexChatApprovalOpening,
): Flow<ChatEvent> = channelFlow {
  val input = Channel<ChatProcessInput>(Channel.UNLIMITED) { it.written.complete(false) }
  val output = Channel<ChatProcessOutput>(Channel.UNLIMITED)
  val readerRef = AtomicReference<kotlinx.coroutines.Job?>()
  val cards = Channel<ApprovalCard>(Channel.UNLIMITED)
  val approvalOpening = try {
    openApproval(
      { line -> input.writeAndAwaitBlocking(line) },
      {
        input.close()
        readerRef.get()?.cancel()
      },
      { cards.trySend(it) },
    )
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (failure: Exception) {
    CodexChatApprovalOpening.Refused(
      "Operator approval could not be opened for Codex: ${failure.message.orEmpty()}",
    )
  }
  val approval = when (approvalOpening) {
    is CodexChatApprovalOpening.Opened -> approvalOpening.approval
    is CodexChatApprovalOpening.Refused -> {
      input.close()
      output.close()
      diagnostics.record("chat.codex_approval_unavailable")
      send(ChatEvent.Failed(ChatFailure.ApprovalUnsupported, approvalOpening.reason))
      return@channelFlow
    }
  }
  val reader = launch(start = CoroutineStart.LAZY) {
    try {
      launcher.openAcknowledged(command, input).collect { output.send(it) }
    } finally {
      output.close()
    }
  }
  readerRef.set(reader)
  reader.start()
  val reply = ChatReplyStream(diagnostics)
  val eventSink: suspend (ChatEvent) -> Unit = { event -> reply.accept(event).forEach { send(it) } }
  val cardsJob = launch { for (card in cards) eventSink(ChatEvent.ApprovalRequested(card)) }
  val rpc = CodexAppServerRpc(input, output, diagnostics, eventSink, approval)
  try {
    val (activeThread, activeTurn) = withTimeout(30_000) {
    rpc.call("initialize", CodexAppServerProtocol.initializeParams()) { }
    if (!input.writeAndAwait(CodexAppServerProtocol.initializedNotification().toString())) {
      throw CodexSessionFailure("Codex initialization delivery is uncertain; it will not be replayed.")
    }
    val settings = codexResumeSettings(codexChatSettings(command, request), savedThread) { method, params ->
      var response: JsonObject? = null
      rpc.call(method, params) { response = it }
      requireNotNull(response)
    }
    var threadId: String? = null
    rpc.call(CodexAppServerProtocol.threadMethod(savedThread), CodexAppServerProtocol.threadParams(settings, savedThread)) { result ->
      val opened = when (val opening = CodexAppServerProtocol.threadOpening(result, settings, savedThread)) {
        is CodexThreadOpening.Refused -> throw CodexSessionFailure(opening.reason)
        is CodexThreadOpening.Opened -> opening.threadId
      }
      threadId = opened
      if (savedThread == null && !saveThread(opened)) {
        throw CodexSessionFailure("Could not save the Codex provider thread.")
      }
    }
    val activeThread = requireNotNull(threadId)
    val turnInput = listOf(CodexAppServerProtocol.textInput(prompt)) + imagePaths.map(CodexAppServerProtocol::localImageInput)
    var turnId: String? = null
    rpc.call("turn/start", CodexAppServerProtocol.turnParams(settings, activeThread, turnInput)) { result ->
      turnId = CodexAppServerProtocol.startedTurnId(result)
        ?: throw CodexSessionFailure("Codex returned a malformed turn/start response.")
    }
    val activeTurn = requireNotNull(turnId)
    activeThread to activeTurn
    }
    approval.bindTurn(activeThread, activeTurn)
    registerActive(rpc, activeThread, activeTurn)
    rpc.drainTurn(activeThread, activeTurn)
  } catch (_: TimeoutCancellationException) {
    approval.stop("Codex did not complete its opening handshake in time.")
    eventSink(ChatEvent.Failed(ChatFailure.ProcessFailed, "Codex did not complete its opening handshake in time."))
  } catch (failure: CodexSessionFailure) {
    approval.stop(failure.message ?: "Codex app-server session failed.")
    diagnostics.record("chat.codex_app_server_refused")
    eventSink(ChatEvent.Failed(ChatFailure.ProcessFailed, failure.message ?: "Codex app-server session failed."))
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (failure: Exception) {
    approval.stop(failure.message ?: "Codex app-server session failed.")
    diagnostics.record("chat.codex_app_server_failed")
    eventSink(ChatEvent.Failed(ChatFailure.ProcessFailed, failure.message ?: "Codex app-server session failed."))
  } finally {
    withContext(NonCancellable) {
      suspend fun cleanup(action: suspend () -> Unit) {
        try {
          action()
        } catch (_: Throwable) {
          diagnostics.record("chat.codex_cleanup_failed")
        }
      }
      cleanup { rpc.closeAndSettleSteering() }
      cleanup { clearActive(rpc) }
      cleanup { input.close() }
      cleanup { reader.cancelAndJoin() }
      cleanup { rpc.drainReadySteeringResponses() }
      cleanup { rpc.releaseSteering() }
      cleanup { releaseRpc(rpc) }
      cleanup { approval.release() }
      cleanup { output.cancel() }
      cleanup { cards.close() }
      cleanup { cardsJob.cancelAndJoin() }
    }
  }
}

internal class CodexAppServerRpc(
  private val input: Channel<ChatProcessInput>,
  private val output: ReceiveChannel<ChatProcessOutput>,
  private val diagnostics: Diagnostics,
  private val emit: suspend (ChatEvent) -> Unit,
  private val approval: CodexChatApproval,
) {
  private var nextId = 1
  private var providerFailure: String? = null
  private var seenProtocolFrame = false
  private val early = ArrayDeque<JsonObject>()
  private val streamedTextItems = mutableMapOf<String, StringBuilder>()
  private val startedTools = mutableSetOf<String>()
  private val itemFacts = LinkedHashMap<CodexChatItemIdentity, JsonObject>()
  private val steering = linkedMapOf<JsonPrimitive, PendingSteer>()
  private var steeringOpen = true

  private data class PendingSteer(
    val deliveryId: String,
    val expectedTurnId: String,
    val frame: ChatProcessInput,
    val result: CompletableDeferred<ChatSteeringResult>,
    val onUpdate: (ChatSteeringResult) -> Unit,
  ) {
    var latest: ChatSteeringResult? = null
    var authoritative = false
  }

  suspend fun call(method: String, params: JsonObject, result: (JsonObject) -> Unit) {
    val id = nextId++
    val written = input.writeAndAwait(buildJsonObject {
      put("id", id)
      put("method", method)
      put("params", params)
    }.toString())
    if (!written) throw CodexSessionFailure("Codex $method delivery is uncertain; it will not be replayed.")
    while (true) {
      val message = nextMessage()
      val requestMethod = CodexAppServerProtocol.rpcRequestMethod(message)
      if (requestMethod == "error" && !CodexAppServerProtocol.hasRpcId(message)) {
        handleErrorNotification(message)
        continue
      }
      if (CodexAppServerProtocol.hasRpcId(message) && CodexAppServerProtocol.hasRpcMethod(message)) {
        if (early.size >= MAX_EARLY_MESSAGES) throw CodexSessionFailure("Codex sent too many early requests.")
        early.addLast(message)
        continue
      }
      if (CodexAppServerProtocol.rpcResponseId(message) == JsonPrimitive(id)) {
        message.obj("error")?.let {
          throw CodexSessionFailure(providerError(it) ?: "$method was rejected by Codex.")
        }
        val body = message.obj("result") ?: throw CodexSessionFailure("Codex returned a malformed $method response.")
        result(body)
        return
      }
      if (early.size >= MAX_EARLY_MESSAGES) {
        throw CodexSessionFailure("Codex sent too many events before its $method response.")
      }
      early.addLast(message)
    }
  }

  suspend fun drainTurn(threadId: String, turnId: String) {
    while (true) {
      val message = if (early.isEmpty()) nextMessage() else early.removeFirst()
      val method = CodexAppServerProtocol.rpcRequestMethod(message)
      if (handleRpcFrame(message)) continue
      if (CodexAppServerProtocol.hasRpcMethod(message) && method == null) {
        throw CodexSessionFailure("Codex app-server sent a malformed method envelope.")
      }
      if (method == null) continue
      if (method == "error") {
        handleErrorNotification(message)
        continue
      }
      val params = message.obj("params") ?: continue
      if (params.string("threadId") != threadId) continue
      val eventTurnId = params.string("turnId") ?: params.obj("turn")?.string("id")
      if (method != "serverRequest/resolved" && eventTurnId != turnId) continue
      when (method) {
        "item/started" -> {
          val item = params.obj("item") ?: continue
          val itemId = item.string("id") ?: continue
          val key = codexItemKey(threadId, requireNotNull(eventTurnId), itemId)
          val existing = itemFacts[key]
          if (existing != null && existing != item) {
            throw CodexSessionFailure("Codex changed an item's action facts after it started.")
          }
          if (itemFacts.size >= MAX_ITEM_FACTS && key !in itemFacts) {
            throw CodexSessionFailure("Codex sent too many item facts before approval.")
          }
          itemFacts[key] = item
        }
        "serverRequest/resolved" -> approval.providerResolved(params)
        "item/completed" -> {
          val item = params.obj("item")
          if (item != null) {
            if (item.string("status") == "declined") providerFailure = "Codex declined the requested action: $item"
            // A failed command is an ordinary tool result the agent repairs; only a declined action ends the turn.
            val tracked = approval.itemCompleted(params, item)
            item.string("id")?.let { itemId -> itemFacts.remove(codexItemKey(threadId, requireNotNull(eventTurnId), itemId)) }
            if (tracked == null) continue
          }
        }
        else -> Unit
      }
      when (method) {
        "item/agentMessage/delta" -> {
          val itemId = params.string("itemId")
          val delta = params.string("delta")
          if (itemId != null && delta != null) {
            if (itemId !in streamedTextItems) emit(ChatEvent.MessageStarted)
            streamedTextItems.getOrPut(itemId) { StringBuilder() }.append(delta)
            emit(ChatEvent.TextDelta(delta))
          }
        }
        "item/started" -> toolCall(params.obj("item"))?.let { call ->
          startedTools += call.id
          emit(ChatEvent.ToolUse(call))
        }
        "item/completed" -> emitCompletedItem(params.obj("item"))
        "turn/completed" -> {
          approval.turnEnded(threadId, turnId)
          closeSteeringAdmission()
          drainReadySteeringResponses()
          settlePendingSteering()
          input.close()
          val turn = params.obj("turn")
          val error = providerError(turn?.obj("error"))
          if (turn?.string("status") == "completed" && providerFailure == null) {
            emit(ChatEvent.Completed)
          } else {
            emit(ChatEvent.Failed(ChatFailure.ProcessFailed, providerFailure ?: error ?: "Codex turn ended without a completed status."))
          }
          drainReadySteeringResponses()
          return
        }
        "turn/failed", "turn/interrupted" -> {
          approval.turnEnded(threadId, turnId)
          closeSteeringAdmission()
          drainReadySteeringResponses()
          settlePendingSteering()
          input.close()
          val detail = providerFailure ?: providerError(params.obj("turn")?.obj("error")) ?: "Codex turn failed."
          emit(ChatEvent.Failed(ChatFailure.ProcessFailed, detail))
          drainReadySteeringResponses()
          return
        }
        "serverRequest/resolved" -> Unit
        else -> diagnostics.record("chat.codex_app_server_unknown_notification")
      }
    }
  }

  suspend fun steer(
    deliveryId: String,
    threadId: String,
    turnId: String,
    inputs: List<JsonObject>,
    acquireWrite: (() -> Boolean) -> Boolean,
    onRegistered: (() -> Boolean) -> Boolean,
    onUpdate: (ChatSteeringResult) -> Unit,
  ): ChatSteeringResult {
    if (synchronized(steering) { !steeringOpen || steering.values.any { it.deliveryId == deliveryId } }) {
      if (!isSteeringOpen()) return ChatSteeringResult.NoActiveTurn(deliveryId, "Codex turn is no longer active")
      return ChatSteeringResult.Rejected(deliveryId, "This delivery is already tracked")
    }
    val id = synchronized(steering) {
      if (!steeringOpen) return ChatSteeringResult.NoActiveTurn(deliveryId, "Codex turn is no longer active")
      nextId++
    }
    val response = CompletableDeferred<ChatSteeringResult>()
    val frame = ChatProcessInput(
      CodexAppServerProtocol.steerRequest(id, threadId, turnId, inputs).toString(),
    ) { input ->
      acquireWrite {
        synchronized(steering) { steeringOpen && input.acquireUnconditionally() }
      }
    }
    val pending = PendingSteer(deliveryId, turnId, frame, response, onUpdate)
    val registered = onRegistered {
      synchronized(steering) {
        if (!steeringOpen) false else {
          steering[JsonPrimitive(id)] = pending
          true
        }
      }
    }
    if (!registered) return ChatSteeringResult.NoActiveTurn(deliveryId, "Codex turn ended before the follow-up was registered")
    try {
      input.send(frame)
    } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
      synchronized(steering) { steering.remove(JsonPrimitive(id)) }
      return ChatSteeringResult.NoActiveTurn(deliveryId, "Codex turn ended before the follow-up was written")
    }
    return select {
      response.onAwait { result -> latestResult(pending, result) }
      frame.written.onAwait { written ->
        if (response.isCompleted) {
          latestResult(pending, response.await())
        } else if (!written) {
          classifyAndComplete(pending)
        } else {
          classifyAndComplete(pending)
        }
      }
    }
  }

  fun closeAndSettleSteering() {
    closeSteeringAdmission()
    settlePendingSteering()
  }

  fun settleSteering(deliveryId: String) {
    synchronized(steering) { steering.values.filter { it.deliveryId == deliveryId }.toList() }
      .forEach(::classifyAndComplete)
  }

  private fun closeSteeringAdmission() {
    synchronized(steering) { steeringOpen = false }
  }

  private fun settlePendingSteering() {
    synchronized(steering) { steering.values.toList() }.forEach { pending ->
      classifyAndComplete(pending)
    }
  }

  fun releaseSteering() {
    synchronized(steering) { steering.clear() }
  }

  internal suspend fun drainReadySteeringResponses() {
    while (true) {
      val outputItem = output.tryReceive().getOrNull() ?: return
      val line = (outputItem as? ChatProcessOutput.Line)?.text ?: continue
      val message = parseStreamLine(line)
      if (message == null) {
        diagnostics.record("chat.codex_app_server_malformed_shutdown_frame")
        continue
      }
      if (!CodexAppServerProtocol.hasRpcId(message) && !CodexAppServerProtocol.hasRpcMethod(message)) {
        diagnostics.record("chat.codex_app_server_malformed_shutdown_frame")
        continue
      }
      try {
        handleRpcFrame(message)
      } catch (_: CodexSessionFailure) {
        diagnostics.record("chat.codex_app_server_malformed_shutdown_frame")
      }
    }
  }

  private suspend fun handleRpcFrame(message: JsonObject): Boolean {
    if (CodexAppServerProtocol.hasRpcId(message) && CodexAppServerProtocol.hasRpcMethod(message)) {
      if (CodexAppServerProtocol.rpcRequestMethod(message) == null) {
        throw CodexSessionFailure("Codex app-server sent a malformed method envelope.")
      }
      approval.handleServerRequest(message, itemFacts)
      return true
    }
    if (CodexAppServerProtocol.hasRpcId(message) && !CodexAppServerProtocol.hasRpcMethod(message)) {
      routeSteeringResponse(message)
      return true
    }
    return false
  }

  private fun routeSteeringResponse(message: JsonObject) {
    val responseId = CodexAppServerProtocol.rpcResponseId(message)
    val pending = responseId?.let { synchronized(steering) { steering[it] } }
    if (pending == null) {
      diagnostics.record("chat.codex_rpc_response_unmatched")
      return
    }
    val outcome = steerResponse(message, pending)
    val authoritative = outcome !is ChatSteeringResult.DeliveryUncertain
    completeSteering(pending, outcome, authoritative)
  }

  private fun completeSteering(
    pending: PendingSteer,
    result: ChatSteeringResult,
    authoritative: Boolean,
  ) {
    val shouldPublish = synchronized(steering) {
      val key = steering.entries.firstOrNull { it.value === pending }?.key
      if (pending.authoritative) return
      if (authoritative) {
        pending.authoritative = true
        pending.latest = result
        key?.let(steering::remove)
        true
      } else if (pending.latest == null) {
        pending.latest = result
        true
      } else {
        false
      }
    }
    pending.result.complete(result)
    if (shouldPublish) {
      try {
        pending.onUpdate(result)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        diagnostics.record("chat.codex_steering_update_failed")
      }
    }
  }

  private fun classifyAndComplete(pending: PendingSteer): ChatSteeringResult {
    val classified = classify(pending)
    completeSteering(pending, classified, classified is ChatSteeringResult.NoActiveTurn)
    return latestResult(pending, classified)
  }

  private fun latestResult(pending: PendingSteer, fallback: ChatSteeringResult): ChatSteeringResult =
    synchronized(steering) { pending.latest ?: fallback }

  private fun classify(pending: PendingSteer): ChatSteeringResult =
    if (pending.frame.revokeBeforeWrite()) {
      pending.frame.written.complete(false)
      ChatSteeringResult.NoActiveTurn(pending.deliveryId, "Codex turn ended before the follow-up was written")
    } else {
      ChatSteeringResult.DeliveryUncertain(pending.deliveryId, "Codex may have received this follow-up; acknowledgement is pending")
    }

  private fun isSteeringOpen(): Boolean = synchronized(steering) { steeringOpen }

  private fun steerResponse(message: JsonObject, pending: PendingSteer): ChatSteeringResult {
    return when (val response = CodexAppServerProtocol.steerResponse(message, pending.expectedTurnId)) {
      is CodexSteerResponse.Accepted ->
        ChatSteeringResult.Accepted(pending.deliveryId)
      is CodexSteerResponse.Rejected ->
        ChatSteeringResult.Rejected(pending.deliveryId, response.diagnostic)
      is CodexSteerResponse.Uncertain ->
        ChatSteeringResult.DeliveryUncertain(pending.deliveryId, response.diagnostic)
    }
  }

  private suspend fun handleErrorNotification(message: JsonObject) {
    val params = message.obj("params") ?: message
    val detail = CodexAppServerProtocol.providerError(params.obj("error"), params["error"])
      .ifBlank { "Codex reported an unspecified app-server error." }
    emit(ChatEvent.Notice(detail))
    val retryValue = params["willRetry"] ?: message["willRetry"]
    val retry = (retryValue as? JsonPrimitive)?.content == "true"
    if (!retry) {
      providerFailure = providerFailure ?: detail
      throw CodexSessionFailure(detail)
    }
  }

  private suspend fun emitCompletedItem(item: JsonObject?) {
    if (item == null) return
    if (item.string("type") == "agentMessage") {
      val itemId = item.string("id") ?: return
      val finalText = item.string("text") ?: return
      val streamed = streamedTextItems[itemId]?.toString().orEmpty()
      when {
        streamed.isEmpty() -> {
          emit(ChatEvent.MessageStarted)
          emit(ChatEvent.TextDelta(finalText))
        }
        finalText.startsWith(streamed) -> finalText.removePrefix(streamed).takeIf(String::isNotEmpty)?.let {
          emit(ChatEvent.TextDelta(it))
        }
        finalText == streamed -> Unit
        else -> throw CodexSessionFailure("Codex returned conflicting streamed and completed assistant text.")
      }
      return
    }
    val call = toolCall(item) ?: return
    if (call.id !in startedTools) emit(ChatEvent.ToolUse(call))
    emit(ChatEvent.ToolResult(call.id, toolOutput(item), toolFailed(item)))
  }

  private fun toolCall(item: JsonObject?): ChatToolCall? {
    if (item == null) return null
    val id = item.string("id") ?: return null
    return when (val type = item.string("type")) {
      null, "userMessage", "agentMessage", "reasoning", "todoList" -> null
      "commandExecution" -> item.string("command").orEmpty().let { ChatToolCall(id, "shell", it, it, item.string("cwd")) }
      "fileChange" -> {
        val changes = (item["changes"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val lines = changes.map { "${it.string("kind") ?: "update"} ${it.string("path").orEmpty()}" }
        ChatToolCall(id, "edit", lines.joinToString("\n"), changes.firstOrNull()?.string("path"))
      }
      "mcpToolCall" -> ChatToolCall(
        id,
        "mcp__${item.string("server")}__${item.string("tool")}",
        item["arguments"]?.renderToolContent().orEmpty(),
        item.string("tool"),
      )
      "webSearch" -> ChatToolCall(id, "websearch", item.string("query").orEmpty(), item.string("query"))
      else -> ChatToolCall(id, type, item.renderToolContent(), null)
    }
  }

  private fun toolOutput(item: JsonObject): String {
    val output = item.string("aggregatedOutput")?.takeIf(String::isNotBlank)
      ?: item["result"]?.renderToolContent()?.takeIf(String::isNotBlank)
    val error = providerError(item.obj("error"))
    return listOfNotNull(
      output,
      error?.takeIf { it != output },
      item.string("status")?.let { "Codex item ended with status $it." }
        ?.takeIf { item.string("status") in setOf("failed", "declined") },
    ).joinToString("\n").ifBlank { item.string("status").orEmpty() }
  }

  private fun toolFailed(item: JsonObject): Boolean = CodexAppServerProtocol.itemFailed(item)

  private suspend fun nextMessage(): JsonObject {
    while (true) {
      when (val item = output.receiveCatching().getOrNull()) {
        is ChatProcessOutput.StdoutEnded -> {
          closeAndSettleSteering()
          val failure = item.readFailure?.let { "Codex app-server stdout failed: $it" }
            ?: "Codex app-server stdout closed before the turn completed."
          val stderr = item.stderrTail.takeLast(1200)
          throw CodexSessionFailure(listOf(failure, stderr).filter(String::isNotBlank).joinToString("\n"))
        }
        is ChatProcessOutput.Line -> {
          val message = parseStreamLine(item.text)
          if (message == null) {
            if (!seenProtocolFrame && !item.text.trimStart().startsWith("{")) {
              diagnostics.record("chat.codex_login_output_skipped")
              continue
            }
            throw CodexSessionFailure("Codex app-server sent a malformed protocol frame.")
          }
          seenProtocolFrame = true
          if (!CodexAppServerProtocol.hasRpcId(message) && !CodexAppServerProtocol.hasRpcMethod(message)) {
            throw CodexSessionFailure("Codex app-server sent a frame without a method or response identity.")
          }
          return message
        }
        is ChatProcessOutput.StartFailed -> {
          closeAndSettleSteering()
          throw CodexSessionFailure(item.message)
        }
        is ChatProcessOutput.Exited -> {
          closeAndSettleSteering()
          throw CodexSessionFailure("Codex app-server exited (${item.code}): ${item.stderrTail.takeLast(1200)}")
        }
        null -> {
          closeAndSettleSteering()
          throw CodexSessionFailure("Codex app-server closed its output before the turn completed.")
        }
      }
    }
  }
}

/** Every detail of a provider error, or null when it carries none. */
private fun providerError(error: JsonObject?): String? =
  CodexAppServerProtocol.providerError(error).takeIf(String::isNotBlank)

private class CodexSessionFailure(message: String) : Exception(message)


private const val MAX_EARLY_MESSAGES = 256
private const val MAX_ITEM_FACTS = 512
