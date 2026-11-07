package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.ApprovalCard
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import skillbill.infrastructure.launcher.process.codex.CodexAppServerProtocol
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
  openApproval: (
    writeLine: (String) -> Boolean,
    stopProcess: () -> Unit,
    onCard: (ApprovalCard) -> Unit,
  ) -> CodexChatApprovalOpening,
): Flow<ChatEvent> = channelFlow {
  codexAppServerCompatibility(command, request, diagnostics)?.let { reason ->
    diagnostics.record("chat.codex_app_server_incompatible")
    send(ChatEvent.Failed(ChatFailure.ApprovalUnsupported, reason))
    return@channelFlow
  }
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
    val settings = codexChatSettings(command, request)
    rpc.call("initialize", CodexAppServerProtocol.initializeParams()) { result ->
      CodexAppServerProtocol.identityRefusal(result)?.let { throw CodexSessionFailure(it) }
    }
    if (!input.writeAndAwait(CodexAppServerProtocol.initializedNotification().toString())) {
      throw CodexSessionFailure("Codex initialization delivery is uncertain; it will not be replayed.")
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
    approval.release()
    input.close()
    reader.cancelAndJoin()
    output.cancel()
    cards.close()
    withContext(NonCancellable) { cardsJob.join() }
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
      if (message.string("method") == "error" && message["id"] == null) {
        handleErrorNotification(message)
        continue
      }
      if (message["id"] != null && message["method"] != null) {
        if (early.size >= MAX_EARLY_MESSAGES) throw CodexSessionFailure("Codex sent too many early requests.")
        early.addLast(message)
        continue
      }
      if (message["id"] == JsonPrimitive(id)) {
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
      val method = message.string("method") ?: continue
      if (message["id"] != null) {
        approval.handleServerRequest(message, itemFacts)
        continue
      }
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
          val turn = params.obj("turn")
          val error = providerError(turn?.obj("error"))
          if (turn?.string("status") == "completed" && providerFailure == null) {
            emit(ChatEvent.Completed)
          } else {
            emit(ChatEvent.Failed(ChatFailure.ProcessFailed, providerFailure ?: error ?: "Codex turn ended without a completed status."))
          }
          return
        }
        "turn/failed", "turn/interrupted" -> {
          approval.turnEnded(threadId, turnId)
          val detail = providerFailure ?: providerError(params.obj("turn")?.obj("error")) ?: "Codex turn failed."
          emit(ChatEvent.Failed(ChatFailure.ProcessFailed, detail))
          return
        }
        "serverRequest/resolved" -> Unit
        else -> diagnostics.record("chat.codex_app_server_unknown_notification")
      }
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
        streamed.isEmpty() -> emit(ChatEvent.TextDelta(finalText))
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
      null, "agentMessage", "reasoning", "todoList" -> null
      "commandExecution" -> item.string("command").orEmpty().let { ChatToolCall(id, "shell", it, it) }
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
          continue
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
          if (message["id"] == null && message["method"] == null) {
            throw CodexSessionFailure("Codex app-server sent a frame without a method or response identity.")
          }
          return message
        }
        is ChatProcessOutput.StartFailed -> throw CodexSessionFailure(item.message)
        is ChatProcessOutput.Exited ->
          throw CodexSessionFailure("Codex app-server exited (${item.code}): ${item.stderrTail.takeLast(1200)}")
        null -> throw CodexSessionFailure("Codex app-server closed its output before the turn completed.")
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
