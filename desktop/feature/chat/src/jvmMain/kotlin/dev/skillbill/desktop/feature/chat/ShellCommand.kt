package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val NAME_LIMIT = 32
private const val ARGUMENT_LIMIT = 24
private const val FALLBACK_NAME = "command"

private val SHELL_TOOLS = setOf("bash", "shell", "exec", "exec_command", "run", "command_execution")
private val COMMAND_KEYS = listOf("command", "cmd")
private val SEGMENT_BREAKS = Regex("[;&|()\\n`]+")
private val WHITESPACE = Regex("\\s+")
private val SHELLS = setOf("sh", "bash", "zsh", "fish", "dash")
private val WRAPPERS = setOf("time", "env", "nice", "nohup", "sudo", "command", "exec", "caffeinate", "timeout", "gtimeout", "xargs")
private val SETUP = setOf("cd", "pushd", "popd", "export", "unset", "set", "source", ".", ":", "echo", "printf", "true", "false")
private val SCRIPT_RUNNERS = setOf("run", "run-script", "exec", "x", "dlx")

/**
 * The shell command of [call], or null when it is not one. Claude and Codex name their shell tool and pass the command as an
 * argument or as the whole input; an ACP agent such as Cursor names the call by its title and keeps it under `rawInput`.
 */
internal fun shellCommand(call: ChatToolCall): String? {
  val shell = call.name.substringAfterLast('.').lowercase() in SHELL_TOOLS
  val args = toolArguments(call.input) ?: return call.input.takeIf { shell && it.isNotBlank() }
  return args.takeIf { shell }?.commandText() ?: (args["rawInput"] as? JsonObject)?.commandText()
}

/**
 * A short readable name for [command]: the program of its first real step and the first thing it acts on, such as
 * `gradlew jvmTest` for `cd desktop && ./gradlew :feature:chat:jvmTest --tests '*Chat*'`. Directory changes, exports and
 * echoes are skipped, a shell's `-c` script is read the same way, and a long result is cut with an ellipsis.
 */
internal fun commandName(command: String): String {
  val name = command.split(SEGMENT_BREAKS).firstNotNullOfOrNull(::segmentName) ?: FALLBACK_NAME
  return if (name.length > NAME_LIMIT) name.take(NAME_LIMIT - 1) + "…" else name
}

private fun segmentName(segment: String): String? {
  val words = segment.trim().split(WHITESPACE).map { it.trim('\'', '"') }.filter { it.isNotEmpty() }
  val start = words.indexOfFirst { it !in WRAPPERS && !it.startsWith("-") && '=' !in it && !it.all(Char::isDigit) }
  if (start < 0) return null
  val program = words[start].replace('\\', '/').substringAfterLast('/').removeSuffix(".bat").removeSuffix(".cmd").removeSuffix(".exe")
  if (program.isEmpty() || program in SETUP) return null
  val rest = words.drop(start + 1)
  val args = rest.filterNot { it.startsWith("-") || '>' in it || '<' in it }
  if (program in SHELLS && rest.any { it.startsWith("-") && !it.startsWith("--") && 'c' in it }) {
    return commandName(args.joinToString(" ")).takeIf { it != FALLBACK_NAME }
  }
  val subject = args.dropWhile { it in SCRIPT_RUNNERS }.firstOrNull()
    ?.replace('\\', '/')?.trimEnd('/')?.substringAfterLast('/')?.let { if (it.startsWith(':')) it.substringAfterLast(':') else it }
    ?.takeIf { it.isNotEmpty() && it.length <= ARGUMENT_LIMIT }
  return if (subject == null) program else "$program $subject"
}

private fun JsonObject.commandText(): String? = COMMAND_KEYS.firstNotNullOfOrNull { key ->
  when (val value = get(key)) {
    is JsonPrimitive -> value.contentOrNull
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(" ")
    else -> null
  }?.takeIf { it.isNotBlank() }
}
