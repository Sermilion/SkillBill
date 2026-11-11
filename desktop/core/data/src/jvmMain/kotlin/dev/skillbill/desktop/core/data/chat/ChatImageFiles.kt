package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.data.runtime.currentDesktopTempRoot
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

private val UNSAFE_NAME_CHARACTERS = Regex("[^A-Za-z0-9._-]")

internal fun chatImageRoot(): Path = currentDesktopTempRoot().resolve("skill-bill-chat-images")

internal fun chatImageDirectory(sessionId: String): Path =
  chatImageRoot().resolve(sessionId.replace(UNSAFE_NAME_CHARACTERS, "_"))

/** The session directory, or null when a session id such as `.` or `..` would put it outside [chatImageRoot]. */
private fun containedDirectory(sessionId: String): Path? =
  chatImageDirectory(sessionId).takeIf { it.normalize().parent == chatImageRoot().normalize() }

/** Writes each image to its own new file under the session directory and returns the absolute real paths, in order. */
internal fun writeChatImages(sessionId: String, images: List<ChatImage>): List<String> {
  val directory = containedDirectory(sessionId) ?: throw IOException("Invalid chat session id")
  Files.createDirectories(directory)
  // Registered before its files: the JVM deletes in reverse order, so the files go first.
  directory.toFile().deleteOnExit()
  return images.map { image ->
    val file = Files.createTempFile(directory, "image-", if (image.mediaType == "image/jpeg") ".jpg" else ".png")
    file.toFile().deleteOnExit()
    Files.write(file, image.bytes)
    file.toRealPath().toString()
  }
}

internal class CodexTurnImages private constructor(private val directory: Path) {
  private val lock = Any()
  private var closed = false

  fun write(images: List<ChatImage>): List<String> = synchronized(lock) {
    check(!closed) { "Codex turn image scope is closed" }
    images.map { image ->
      val file = Files.createTempFile(directory, "image-", if (image.mediaType == "image/jpeg") ".jpg" else ".png")
      file.toFile().deleteOnExit()
      Files.write(file, image.bytes)
      file.toRealPath().toString()
    }
  }

  @OptIn(ExperimentalPathApi::class)
  fun close() = synchronized(lock) {
    if (closed) return
    closed = true
    directory.deleteRecursively()
  }

  companion object {
    fun create(sessionId: String): CodexTurnImages {
      val parent = containedDirectory(sessionId) ?: throw IOException("Invalid chat session id")
      Files.createDirectories(parent)
      val directory = Files.createTempDirectory(parent, "codex-turn-")
      directory.toFile().deleteOnExit()
      return CodexTurnImages(directory)
    }
  }
}

/** The operator [text] followed by one line per image path, or just those lines when the text is blank. */
internal fun withImageFooter(text: String, paths: List<String>): String {
  val block = "Attached images (open each file to view it):\n" +
    paths.mapIndexed { index, path -> "[Image #${index + 1}] $path" }.joinToString("\n")
  return if (text.isBlank()) block else "$text\n\n$block"
}

/**
 * Removes the session's image directory; a missing directory or a failed delete is ignored. The temp root can be shared
 * with other users, so symbolic links are deleted as links and never followed: a link planted at or inside the session
 * directory cannot redirect the delete to files outside it.
 */
@OptIn(ExperimentalPathApi::class)
internal fun deleteChatImages(sessionId: String) {
  val directory = containedDirectory(sessionId) ?: return
  runCatching { directory.deleteRecursively() }
}

/**
 * Runs [turn] with the operator text, plus the paths of the request's images written to temp files. With no images [turn]
 * gets the prompt unchanged and nothing is written. A failed write ends the turn before any process starts.
 */
internal fun turnWithImages(request: ChatTurnRequest, turn: (String) -> Flow<ChatEvent>): Flow<ChatEvent> = flow {
  if (request.images.isEmpty()) {
    emitAll(turn(request.prompt))
    return@flow
  }
  val paths = try {
    writeChatImages(request.sessionId, request.images)
  } catch (error: IOException) {
    emit(ChatEvent.Failed(ChatFailure.ProcessFailed, "Could not save a pasted image: ${error.message}"))
    return@flow
  }
  emitAll(turn(withImageFooter(request.prompt, paths)))
}
