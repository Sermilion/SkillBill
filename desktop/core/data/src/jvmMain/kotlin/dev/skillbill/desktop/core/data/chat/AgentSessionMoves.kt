package dev.skillbill.desktop.core.data.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Sessions one store move changed and sessions it could not. */
internal data class StoreMove(val moved: Int, val failed: Int)

private const val NEWLINE = '\n'.code

/** Runs [action] and reports whether it finished; the I/O failures a move expects count as a failed session, not an exception. */
private inline fun attempt(action: () -> Unit): Boolean = try {
  action()
  true
} catch (_: IOException) {
  false
} catch (_: SecurityException) {
  false
}

/**
 * Moves every `<id>.jsonl` of [fromRoot]'s Claude project directory in [profile], with its `<id>/` directory, into [toRoot]'s.
 * A session already at the destination stays where it is; `memory/` and every other entry are left alone. Line contents are not
 * rewritten.
 */
internal fun moveClaudeSessions(profile: File, fromRoot: String, toRoot: String): StoreMove {
  if (claudeProjectName(fromRoot) == claudeProjectName(toRoot)) return StoreMove(0, 0)
  val source = File(profile, "projects/${claudeProjectName(fromRoot)}")
  val sessions = source.listFiles { file -> file.isFile && file.name.endsWith(".jsonl") }.orEmpty()
  if (sessions.isEmpty()) return StoreMove(0, 0)
  val target = File(profile, "projects/${claudeProjectName(toRoot)}")
  Files.createDirectories(target.toPath())
  var moved = 0
  var failed = 0
  for (file in sessions) {
    val destination = File(target, file.name)
    if (destination.exists()) continue
    val id = file.name.removeSuffix(".jsonl")
    // The .jsonl moves last: its presence at the destination is the commit point, so an interrupted move is finished next time.
    val done = attempt {
      val folder = File(source, id)
      val folderDestination = File(target, id)
      if (folder.isDirectory && !folderDestination.exists()) Files.move(folder.toPath(), folderDestination.toPath())
      Files.move(file.toPath(), destination.toPath())
    }
    if (done) moved++ else failed++
  }
  if (source.list()?.isEmpty() == true) attempt { Files.deleteIfExists(source.toPath()) }
  return StoreMove(moved, failed)
}

/**
 * Sets `payload.cwd` of every Codex `session_meta` line in [profile] from [fromRoot] to [toRoot], in the newest sessions that
 * [codexSessions] scans. Only the first line of a matching file changes; later lines and the file's modified time stay as they were.
 */
internal fun rewriteCodexSessions(profile: File, fromRoot: String, toRoot: String): StoreMove {
  if (fromRoot == toRoot) return StoreMove(0, 0)
  var rewritten = 0
  var failed = 0
  for (file in newestFirst(File(profile, "sessions")).take(CODEX_SCAN_LIMIT)) {
    var changed = false
    if (attempt { changed = rewriteFirstLine(file, fromRoot, toRoot) }) {
      if (changed) rewritten++
    } else {
      failed++
    }
  }
  return StoreMove(rewritten, failed)
}

/**
 * Rewrites [file]'s first line when it is a `session_meta` line whose cwd is [fromRoot]. A temp file is created only for a match,
 * so the sessions a move merely scans are never written to and cannot fail it.
 */
private fun rewriteFirstLine(file: File, fromRoot: String, toRoot: String): Boolean {
  val modified = Files.getLastModifiedTime(file.toPath())
  var temp: Path? = null
  try {
    val written = file.inputStream().buffered().use { input ->
      val head = ByteArrayOutputStream()
      var next = input.read()
      while (next != -1 && next != NEWLINE) {
        head.write(next)
        next = input.read()
      }
      val text = head.toString(Charsets.UTF_8)
      val terminator = (if (next == -1) "" else "\n").let { if (text.endsWith("\r")) "\r$it" else it }
      val updated = withCwd(parseStreamLine(text.removeSuffix("\r")), fromRoot, toRoot) ?: return false
      val created = Files.createTempFile(file.parentFile.toPath(), ".", ".tmp").also { temp = it }
      created.toFile().outputStream().buffered().use { out ->
        out.write((Json.encodeToString(JsonObject.serializer(), updated) + terminator).toByteArray(Charsets.UTF_8))
        input.copyTo(out)
      }
      created
    }
    Files.move(written, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    Files.setLastModifiedTime(file.toPath(), modified)
    return true
  } finally {
    temp?.let(Files::deleteIfExists)
  }
}

/** [meta] with its payload cwd set to [toRoot], or null when it is not a `session_meta` line whose cwd is [fromRoot]. */
private fun withCwd(meta: JsonObject?, fromRoot: String, toRoot: String): JsonObject? {
  val payload = meta?.takeIf { it.string("type") == "session_meta" }?.obj("payload")?.takeIf { it.string("cwd") == fromRoot } ?: return null
  return JsonObject(meta + ("payload" to JsonObject(payload + ("cwd" to JsonPrimitive(toRoot)))))
}
