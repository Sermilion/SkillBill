package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.usage.UsageObservationSink
import dev.skillbill.desktop.core.domain.model.ApprovalCard
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.usage.model.RequestPermission
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64
import kotlin.time.Clock

/**
 * What a live process was started for: its permission mode and effective Claude config directory (empty means the
 * default account). A turn whose binding differs needs a new process, and so a new approval generation.
 */
internal data class ChatProcessBinding(val mode: ChatPermissionMode, val configDir: String)

/**
 * One `claude` process kept across a chat session's turns. Claude replays each operator message with the uuid the app gave
 * it; the output after that replay, up to its `result`, belongs to the turn that sent it. Output that starts while no turn
 * owns the stream, such as a reply after a background command finished, goes to [unprompted]. Only the reader calls [accept]
 * and [abandon]; turns call [register] from any thread.
 */
internal class ClaudeLiveSession(
  val binding: ChatProcessBinding,
  val workingDirectory: String,
  private val diagnostics: Diagnostics,
  private val logicalSessionId: String,
  val processGeneration: Long,
  private val usageSink: UsageObservationSink,
  private val clock: Clock,
  private val unprompted: (ChatEvent) -> Unit,
) {
  val input = Channel<ChatProcessInput>(Channel.UNLIMITED) { it.written.complete(false) }
  lateinit var job: Job

  /** Completed once the provider answered the initialize request successfully; the first user frame waits for it. */
  val initializedSignal = CompletableDeferred<Unit>()

  /** The broker generation of this process; a permission request with none bound is denied, never answered silently. */
  var approval: ClaudeChatApproval? = null

  private val lock = Any()
  private val pending = HashMap<String, SendChannel<ChatEvent>>()
  private var closed = false
  private var initialized = false
  private val held = ArrayList<String>()

  private var target: SendChannel<ChatEvent>? = null
  private var unpromptedOpen = false
  private var reply: ChatReplyStream? = null
  private var streamStarted = false
  private var wroteText = false
  private var usageDiagnosticRecorded = false

  /** Routes the reply to the message [uuid] into [sink]; false once the process ended, so the caller starts a new one. */
  fun register(uuid: String, sink: SendChannel<ChatEvent>): Boolean = synchronized(lock) {
    if (closed) return false
    pending[uuid] = sink
    true
  }

  /** A turn's user frame is held until the initialize exchange succeeded, then flushed in order. */
  fun send(uuid: String, text: String, images: List<ChatImage>) {
    val frame = ChatProcessInput(userMessage(uuid, text, images))
    synchronized(lock) {
      if (initialized) input.trySend(frame).onFailure { frame.written.complete(false) } else held.add(frame.line)
    }
  }

  /** The initialize request was answered successfully: sends every held user frame, then sends directly. */
  private fun initializedByProvider() {
    synchronized(lock) {
      if (initialized) return
      initialized = true
      held.forEach { line ->
        val frame = ChatProcessInput(line)
        input.trySend(frame).onFailure { frame.written.complete(false) }
      }
      held.clear()
    }
    initializedSignal.complete(Unit)
  }

  /**
   * The provider cannot host permission control: every waiting turn fails visibly with [message] and the process is
   * stopped. May be called from any thread; it touches only the waiting turns under the lock.
   */
  fun failStartup(message: String) {
    val failure = ChatEvent.Failed(ChatFailure.ProcessFailed, message)
    val waiting = close()
    waiting.forEach {
      it.trySend(failure)
      it.close()
    }
    approval?.stop(message)
  }

  fun accept(output: ChatProcessOutput) {
    when (output) {
      is ChatProcessOutput.Line -> line(output.text)
      is ChatProcessOutput.StdoutEnded -> Unit
      is ChatProcessOutput.Exited -> exited(exitFailure(output.code, output.stderrTail))
      is ChatProcessOutput.StartFailed -> exited(ChatEvent.Failed(ChatFailure.ProcessFailed, output.message))
    }
  }

  /**
   * Closes every waiting turn without an answer; the reader calls it when the process ends or the app stops it. A reply the
   * agent started on its own just stops, since the chat that stopped the process already moved on.
   */
  fun abandon() {
    val waiting = close()
    approval?.release()
    target?.close()
    target = null
    unpromptedOpen = false
    waiting.forEach { it.close() }
  }

  private fun line(text: String) {
    if (synchronized(lock) { closed }) return
    // The login shell's profile can print to stdout before claude starts; only lines after the first JSON object are the stream.
    if (!streamStarted) {
      streamStarted = text.trimStart().startsWith("{")
      if (!streamStarted) {
        if (text.isNotBlank()) diagnostics.record("chat.login_output_skipped")
        return
      }
    }
    val root = parseStreamLine(text)
    if (root?.string("type") == RATE_LIMIT_EVENT) {
      observeRateLimit(root)
      return
    }
    if (root != null && root.string("type") == "control_request") {
      approval?.handle(root, text) ?: diagnostics.record("chat.control_request_without_approval")
      return
    }
    if (root != null && root.string("type") == "control_response") {
      when (val outcome = approval?.handleControlResponse(root)) {
        ChatInitializeOutcome.Initialized -> initializedByProvider()
        is ChatInitializeOutcome.Refused -> failStartup(outcome.message)
        ChatInitializeOutcome.Unrelated, null -> Unit
      }
      return
    }
    if (root != null && root.string("type") == "user" && (root["isReplay"] as? JsonPrimitive)?.booleanOrNull == true) {
      val sink = synchronized(lock) { root.string("uuid")?.let(pending::remove) } ?: return
      if (unpromptedOpen) emit(ChatEvent.Completed)
      target = sink
      openReply()
      return
    }
    val decoded = decodeClaudeLine(text)
    val events = buildList {
      if (ChatEvent.MessageStarted in decoded) wroteText = false
      if (wroteText && root?.startsClaudeTextBlock() == true) add(ChatEvent.TextDelta(TEXT_BLOCK_SEPARATOR))
      addAll(decoded)
    }
    if (events.isEmpty()) return
    val malformed = events.filterIsInstance<ChatEvent.Failed>()
      .firstOrNull { it.failure == ChatFailure.StreamMalformed }
    if (malformed != null) {
      failMalformedStream(malformed)
      return
    }
    if (target == null && !unpromptedOpen) {
      unpromptedOpen = true
      openReply()
    }
    if (events.any { it is ChatEvent.TextDelta }) wroteText = true
    events.forEach(::emit)
  }

  private fun observeRateLimit(root: JsonObject) {
    val eventSessionId = root.string("session_id")
    val status = root.obj("rate_limit_info")?.string("status")
    if (("session_id" in root && eventSessionId != logicalSessionId) || status == null) {
      recordUsageDiagnostic()
      return
    }
    val permission = when (status) {
      "allowed", "allowed_warning" -> RequestPermission.Allowed
      "rejected" -> RequestPermission.Rejected
      else -> {
        recordUsageDiagnostic()
        return
      }
    }
    usageSink.recordSessionStatus(
      logicalSessionId,
      processGeneration,
      permission,
      status == "allowed_warning",
      clock.now(),
    )
  }

  private fun recordUsageDiagnostic() {
    if (usageDiagnosticRecorded) return
    usageDiagnosticRecorded = true
    diagnostics.record("usage.claude_event_invalid")
  }

  private fun failMalformedStream(failure: ChatEvent.Failed) {
    val active: Pair<SendChannel<ChatEvent>?, ChatReplyStream?>
    val waiting: List<SendChannel<ChatEvent>>
    val reportUnprompted: Boolean
    synchronized(lock) {
      if (closed) return
      closed = true
      active = target to reply
      reportUnprompted = target == null && pending.isEmpty()
      waiting = pending.values.toList()
      pending.clear()
      target = null
      reply = null
      unpromptedOpen = false
    }
    input.cancel()
    val (sink, stream) = active
    val events = stream?.accept(failure) ?: listOf(failure)
    if (sink != null) {
      events.forEach { sink.trySend(it) }
      sink.close()
    } else if (reportUnprompted) {
      events.forEach(unprompted)
    }
    waiting.forEach { channel ->
      channel.trySend(failure)
      channel.close()
    }
    approval?.stop("Claude emitted malformed stream output; its process was stopped.")
  }

  /**
   * Shows a permission card in the turn that owns the stream, else through [unprompted]. A card is not reply text: it
   * neither opens nor closes a reply, so a card arriving between turns never leaves a turn waiting for a completion. Only
   * the reader calls it, from the control request it is handling.
   */
  fun showCard(card: ApprovalCard) {
    val event = ChatEvent.ApprovalRequested(card)
    val sink = target
    if (sink != null) sink.trySend(event) else unprompted(event)
  }

  private fun openReply() {
    reply = ChatReplyStream(diagnostics)
    wroteText = false
  }

  private fun emit(event: ChatEvent) {
    val stream = reply ?: return
    for (shown in stream.accept(event)) {
      val sink = target
      if (sink != null) sink.trySend(shown) else unprompted(shown)
    }
    if (event is ChatEvent.Completed || event is ChatEvent.Failed) {
      target?.close()
      target = null
      unpromptedOpen = false
      reply = null
    }
  }

  private fun exited(failure: ChatEvent.Failed) {
    val waiting = close()
    approval?.release()
    when {
      target != null || unpromptedOpen -> emit(failure)
      waiting.isEmpty() -> diagnostics.record("chat.live_process_exited")
      else -> Unit
    }
    waiting.forEach {
      it.trySend(failure)
      it.close()
    }
  }

  private fun close(): List<SendChannel<ChatEvent>> = synchronized(lock) {
    closed = true
    pending.values.toList().also { pending.clear() }
  }.also { input.cancel() }
}

private const val RATE_LIMIT_EVENT = "rate_limit_event"

private fun userMessage(uuid: String, text: String, images: List<ChatImage>): String = buildJsonObject {
  put("type", "user")
  put("uuid", uuid)
  putJsonObject("message") {
    put("role", "user")
    if (images.isEmpty()) {
      put("content", text)
    } else {
      putJsonArray("content") {
        if (text.isNotBlank()) {
          addJsonObject {
            put("type", "text")
            put("text", text)
          }
        }
        images.forEach { image ->
          addJsonObject {
            put("type", "image")
            putJsonObject("source") {
              put("type", "base64")
              put("media_type", image.mediaType)
              // The basic encoder is standard Base64 without line breaks; the MIME encoder would wrap lines.
              put("data", Base64.getEncoder().encodeToString(image.bytes))
            }
          }
        }
      }
    }
  }
}.toString()

private const val TEXT_BLOCK_SEPARATOR = "\n\n"
