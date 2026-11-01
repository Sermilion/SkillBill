package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * One `claude` process kept across a chat session's turns. Claude replays each operator message with the uuid the app gave
 * it; the output after that replay, up to its `result`, belongs to the turn that sent it. Output that starts while no turn
 * owns the stream, such as a reply after a background command finished, goes to [unprompted]. Only the reader calls [accept]
 * and [abandon]; turns call [register] from any thread.
 */
internal class ClaudeLiveSession(
  private val diagnostics: Diagnostics,
  private val unprompted: (ChatEvent) -> Unit,
) {
  val input = Channel<String>(Channel.UNLIMITED)
  lateinit var job: Job

  private val lock = Any()
  private val pending = HashMap<String, SendChannel<ChatEvent>>()
  private var closed = false

  private var target: SendChannel<ChatEvent>? = null
  private var unpromptedOpen = false
  private var reply: ChatReplyStream? = null
  private var streamStarted = false

  /** Routes the reply to the message [uuid] into [sink]; false once the process ended, so the caller starts a new one. */
  fun register(uuid: String, sink: SendChannel<ChatEvent>): Boolean = synchronized(lock) {
    if (closed) return false
    pending[uuid] = sink
    true
  }

  fun send(uuid: String, text: String) {
    input.trySend(userMessage(uuid, text))
  }

  fun accept(output: ChatProcessOutput) {
    when (output) {
      is ChatProcessOutput.Line -> line(output.text)
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
    target?.close()
    target = null
    unpromptedOpen = false
    waiting.forEach { it.close() }
  }

  private fun line(text: String) {
    // The login shell's profile can print to stdout before claude starts; only lines after the first JSON object are the stream.
    if (!streamStarted) {
      streamStarted = text.trimStart().startsWith("{")
      if (!streamStarted) {
        if (text.isNotBlank()) diagnostics.record("chat.login_output_skipped")
        return
      }
    }
    val root = parseStreamLine(text)
    if (root != null && root.string("type") == "user" && (root["isReplay"] as? JsonPrimitive)?.booleanOrNull == true) {
      val sink = synchronized(lock) { root.string("uuid")?.let(pending::remove) } ?: return
      if (unpromptedOpen) emit(ChatEvent.Completed)
      target = sink
      reply = ChatReplyStream(diagnostics)
      return
    }
    val events = decodeClaudeLine(text)
    if (events.isEmpty()) return
    if (target == null && !unpromptedOpen) {
      unpromptedOpen = true
      reply = ChatReplyStream(diagnostics)
    }
    events.forEach(::emit)
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
  }.also { input.close() }
}

private fun userMessage(uuid: String, text: String): String = buildJsonObject {
  put("type", "user")
  put("uuid", uuid)
  putJsonObject("message") {
    put("role", "user")
    put("content", text)
  }
}.toString()
