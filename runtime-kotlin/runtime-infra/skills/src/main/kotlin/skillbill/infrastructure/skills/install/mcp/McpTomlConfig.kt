package skillbill.infrastructure.skills.install.mcp

import skillbill.infrastructure.host.jvm.atomicWriteString
import skillbill.install.model.McpMutationResult
import skillbill.ports.repository.toFileLocation
import java.nio.file.Files
import java.nio.file.Path

object McpTomlConfig {
  private const val UNICODE_ESCAPE_DIGITS = 4
  private const val HEXADECIMAL_RADIX = 16
  private const val LONG_UNICODE_ESCAPE_DIGITS = 8
  private const val MULTILINE_QUOTE_COUNT = 3

  fun register(
    agent: String,
    path: Path,
    command: String,
  ): McpMutationResult {
    val beforeContent = if (Files.exists(path)) Files.readString(path) else ""
    val filtered =
      removeProductServerSections(readLines(path))
        .dropLastWhile { it.isBlank() }
        .toMutableList()
    filtered += ""
    filtered += "[${McpTomlKeys.MCP_SERVERS}.${McpConfigKeys.SONA_SERVER}]"
    filtered += "${McpConfigKeys.COMMAND} = \"${tomlString(command)}\""
    filtered += "${McpConfigKeys.ARGS} = []"
    filtered += ""
    val afterContent = filtered.joinToString("\n")
    val changed = beforeContent != afterContent
    if (changed) atomicWriteString(path, afterContent)
    return McpMutationResult(agent, path.toFileLocation(), changed = changed)
  }

  fun unregister(
    agent: String,
    path: Path,
  ): McpMutationResult {
    val lines = readLines(path)
    val filtered = removeProductServerSections(lines)
    val changed = filtered != lines
    if (changed) writeLines(path, filtered.dropLastWhile { it.isBlank() } + "")
    return McpMutationResult(agent, path.toFileLocation(), changed = changed)
  }

  data class GovernedServer(
    val serverName: String,
    val command: String,
    val args: List<String>,
    val env: Map<String, String>,
    val enabledTools: List<String>,
  )

  fun writeGovernedServer(
    path: Path,
    server: GovernedServer,
  ) {
    val lines =
      buildList {
        add("[${McpTomlKeys.MCP_SERVERS}.${server.serverName}]")
        add("${McpConfigKeys.COMMAND} = \"${tomlString(server.command)}\"")
        add("${McpConfigKeys.ARGS} = [${server.args.joinToString(", ") { "\"${tomlString(it)}\"" }}]")
        add("enabled_tools = [${server.enabledTools.joinToString(", ") { "\"${tomlString(it)}\"" }}]")
        add("[${McpTomlKeys.MCP_SERVERS}.${server.serverName}.env]")
        server.env.forEach { (key, value) -> add("$key = \"${tomlString(value)}\"") }
      }
    writeLines(path, lines)
  }

  private fun removeProductServerSections(lines: List<String>): List<String> {
    val filtered = mutableListOf<String>()
    var skippingSelectedTable = false
    var multilineDelimiter: String? = null
    lines.forEach { line ->
      val tablePath = if (multilineDelimiter == null) parseTablePath(line) else null
      if (tablePath != null) skippingSelectedTable = isProductServerTable(tablePath)
      if (!skippingSelectedTable) filtered += line
      multilineDelimiter = nextMultilineDelimiter(line, multilineDelimiter)
    }
    return filtered
  }

  private fun isProductServerTable(path: List<String>): Boolean =
    path.size >= 2 && path[0] == McpTomlKeys.MCP_SERVERS && path[1] in McpTomlKeys.PRODUCT_SERVERS

  private fun parseTablePath(line: String): List<String>? {
    val header = line.trimStart()
    if (!header.startsWith("[")) return null
    val array = header.startsWith("[[")
    val openLength = if (array) 2 else 1
    val closeToken = if (array) "]]" else "]"
    val close = findTableHeaderClose(header, openLength, closeToken) ?: return null
    if (header.substring(close + closeToken.length).substringBefore('#').isNotBlank()) return null
    return parseDottedKey(header.substring(openLength, close).trim())
  }

  private fun parseDottedKey(body: String): List<String>? {
    val path = mutableListOf<String>()
    var index = 0
    while (index < body.length) {
      val match = TABLE_KEY.matchAt(body, index) ?: return null
      val component =
        match.groups["bare"]?.value
          ?: match.groups["basic"]?.value?.let(::decodeBasicKey)
          ?: match.groups["literal"]?.value
          ?: return null
      path += component
      index = match.range.last + 1
      if (match.groups["separator"]?.value == "." && index == body.length) return null
    }
    return path.takeIf { it.isNotEmpty() }
  }

  private fun decodeBasicKey(key: String): String? {
    val decoded = StringBuilder()
    var index = 0
    while (index < key.length) {
      val character = key[index++]
      if (character != '\\') {
        decoded.append(character)
      } else {
        val escape = key.getOrNull(index++) ?: return null
        if (escape == 'u' || escape == 'U') {
          val digits = if (escape == 'u') UNICODE_ESCAPE_DIGITS else LONG_UNICODE_ESCAPE_DIGITS
          val codePoint =
            key.substring(index, (index + digits).coerceAtMost(key.length))
              .takeIf { it.length == digits }
              ?.toIntOrNull(HEXADECIMAL_RADIX)
              ?.takeIf { Character.isValidCodePoint(it) && it !in Char.MIN_SURROGATE.code..Char.MAX_SURROGATE.code }
              ?: return null
          decoded.appendCodePoint(codePoint)
          index += digits
        } else {
          decoded.append(BASIC_KEY_ESCAPES[escape] ?: return null)
        }
      }
    }
    return decoded.toString()
  }

  private fun findTableHeaderClose(
    header: String,
    start: Int,
    closeToken: String,
  ): Int? {
    var quote: Char? = null
    var index = start
    while (index < header.length) {
      val character = header[index]
      if (quote != null) {
        if (character == quote) {
          quote = null
        } else if (character == '\\' && quote == '"' && index + 1 < header.length) {
          index++
        }
      } else if (character == '\'' || character == '"') {
        quote = character
      } else if (header.startsWith(closeToken, index)) {
        return index
      } else if (character == '#') {
        return null
      }
      index++
    }
    return null
  }

  private fun nextMultilineDelimiter(
    line: String,
    activeDelimiter: String?,
  ): String? {
    var delimiter = activeDelimiter
    var index = 0
    while (index < line.length) {
      if (delimiter != null) {
        val end = findMultilineClose(line, delimiter, index) ?: return delimiter
        index = end + MULTILINE_QUOTE_COUNT
        while (index < line.length && line[index] == delimiter[0]) index++
        delimiter = null
      } else if (line[index] == '#') {
        break
      } else if (line.startsWith("\"\"\"", index) || line.startsWith("'''", index)) {
        delimiter = line.substring(index, index + MULTILINE_QUOTE_COUNT)
        index += MULTILINE_QUOTE_COUNT
      } else if (line[index] == '\'' || line[index] == '\"') {
        index = skipQuotedString(line, index)
      } else {
        index++
      }
    }
    return delimiter
  }

  private fun skipQuotedString(
    line: String,
    start: Int,
  ): Int {
    val quote = line[start]
    var index = start + 1
    while (index < line.length && line[index] != quote) {
      if (line[index] == '\\' && quote == '"' && index + 1 < line.length) index++
      index++
    }
    return index + 1
  }

  private fun findMultilineClose(
    line: String,
    delimiter: String,
    start: Int,
  ): Int? {
    var end = line.indexOf(delimiter, start)
    while (end >= 0 && delimiter == "\"\"\"" && isEscaped(line, end)) {
      end = line.indexOf(delimiter, end + 1)
    }
    return end.takeIf { it >= 0 }
  }

  private fun isEscaped(
    line: String,
    index: Int,
  ): Boolean {
    var start = index
    while (start > 0 && line[start - 1] == '\\') start--
    return (index - start) % 2 != 0
  }

  private fun readLines(path: Path): List<String> = if (Files.exists(path)) Files.readAllLines(path) else emptyList()

  private fun writeLines(
    path: Path,
    lines: List<String>,
  ) {
    atomicWriteString(path, lines.joinToString("\n"))
  }

  private fun tomlString(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

  private val TABLE_KEY =
    Regex("""\s*(?:(?<bare>[A-Za-z0-9_-]+)|"(?<basic>(?:[^"\\]|\\.)*)"|'(?<literal>[^']*)')\s*(?<separator>\.|$)""")
  private val BASIC_KEY_ESCAPES =
    mapOf('b' to '\b', 't' to '\t', 'n' to '\n', 'f' to '\u000C', 'r' to '\r', '"' to '"', '\\' to '\\')
}

private object McpTomlKeys {
  const val MCP_SERVERS = "mcp_servers"
  val PRODUCT_SERVERS = setOf(McpConfigKeys.SKILL_BILL_SERVER, McpConfigKeys.SONA_SERVER)
}
