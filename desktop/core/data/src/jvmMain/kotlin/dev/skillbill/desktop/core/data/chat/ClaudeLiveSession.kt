package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * One `claude` process kept across a chat session's turns. Claude replays each operator message with the uuid the app gave
 * it; the output after that replay, up to its `result`, belongs to the turn that sent it. Output that starts while no turn
 * owns the stream, such as a reply after a background command finished, goes to [unprompted]. Only the reader calls [accept]
 * and [abandon]; turns call [register] from any thread.
 */
internal class ClaudeLiveSession(
  val workingDirectory: String,
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
  private var wroteText = false

  /** Routes the reply to the message [uuid] into [sink]; false once the process ended, so the caller starts a new one. */
  fun register(uuid: String, sink: SendChannel<ChatEvent>): Boolean = synchronized(lock) {
    if (closed) return false
    pending[uuid] = sink
    true
  }

  fun send(uuid: String, text: String, images: List<ChatImage>) {
    input.trySend(userMessage(uuid, text, images))
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
    if (target == null && !unpromptedOpen) {
      unpromptedOpen = true
      openReply()
    }
    if (events.any { it is ChatEvent.TextDelta }) wroteText = true
    events.forEach(::emit)
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
