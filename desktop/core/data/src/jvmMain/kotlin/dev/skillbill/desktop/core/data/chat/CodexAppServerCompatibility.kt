package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.data.runtime.currentDesktopTempRoot
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import skillbill.infrastructure.launcher.process.codex.CodexSchemaAdmission
import skillbill.infrastructure.launcher.process.codex.CodexThreadSettings
import skillbill.infrastructure.launcher.process.codex.codexVersionRefusal
import java.io.IOException
import java.nio.file.Files
import java.util.Comparator

/**
 * Admits the installed Codex CLI for a chat turn: the supported version, and a generated app-server schema that
 * accepts every shape the chat sends and reads, as agent runs check it, plus local image inputs.
 */
internal suspend fun codexAppServerCompatibility(
  command: ChatCommand,
  request: ChatTurnRequest,
  diagnostics: Diagnostics,
): String? =
  withContext(Dispatchers.IO) {
    val base = command.probeInvocation
    val version = runChatProcessProbe(command, base + "--version")
    if (version.exitCode != 0) {
      return@withContext "Could not read the installed Codex CLI version."
    }
    codexVersionRefusal(version.stdout)?.let { return@withContext it }
    val directory = try {
      Files.createTempDirectory(currentDesktopTempRoot(), "skill-bill-codex-schema-")
    } catch (failure: IOException) {
      return@withContext "Could not inspect the installed Codex app-server protocol: ${failure.message.orEmpty()}"
    }
    try {
      val generated = runChatProcessProbe(
        command,
        base + listOf("app-server", "generate-json-schema", "--experimental", "--out", directory.toString()),
      )
      if (generated.exitCode != 0) {
        return@withContext "The installed Codex CLI could not expose its app-server protocol schema."
      }
      CodexSchemaAdmission.inspect(directory, codexChatSettings(command, request), localImages = true)
    } finally {
      try {
        Files.walk(directory).use { paths ->
          paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
      } catch (_: IOException) {
        diagnostics.record("chat.codex_schema_cleanup_failed")
      }
    }
  }

/** Uses the same native bypass settings for new and resumed operator chats. */
internal fun codexChatSettings(command: ChatCommand, request: ChatTurnRequest): CodexThreadSettings {
  return CodexThreadSettings(
    cwd = command.workingDirectory,
    model = request.agent.model,
    effort = request.agent.effort,
  )
}
