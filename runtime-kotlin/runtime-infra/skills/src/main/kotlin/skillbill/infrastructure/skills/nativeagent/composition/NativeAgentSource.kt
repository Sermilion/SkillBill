package skillbill.infrastructure.skills.nativeagent.composition

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.infrastructure.skills.nativeagent.rendering.YAML_DOUBLE_QUOTE_ESCAPES
import java.nio.file.Files
import java.nio.file.Path

const val NATIVE_AGENT_SOURCE_DIR = "native-agents"
const val NATIVE_AGENT_BUNDLE_FILE = "agents.yaml"
private const val FRONTMATTER_OPEN_LENGTH = 4

data class NativeAgentSource(
  val name: String,
  val description: String,
  val body: String,
  val composition: NativeAgentCompositionDirective? = null,
  val path: Path? = null,
  val bundleEntryName: String? = null,
  val tools: List<String> = emptyList(),
  val composedAddonSlugs: List<String> = emptyList(),
)

internal val MUTATING_TOOL_NAMES: Set<String> = setOf("Edit", "Write", "NotebookEdit", "Agent")

val NativeAgentSource.declaresReadOnlyToolset: Boolean
  get() = tools.isNotEmpty() && tools.none { tool -> tool in MUTATING_TOOL_NAMES }

data class NativeAgentCompositionDirective(
  val kind: NativeAgentCompositionKind,
)

enum class NativeAgentCompositionKind(val wireValue: String) {
  GovernedContent("governed-content"),
}

fun parseNativeAgentSource(path: Path): NativeAgentSource {
  val text = Files.readString(path)
  val parsed = parseNativeAgentSourceText(text, path.toString())
  val expectedFileName = "${parsed.name}.md"
  if (path.fileName.toString() != expectedFileName) {
    invalidNativeAgentSourceInput("$path: native agent source filename must match frontmatter name '${parsed.name}'")
  }
  return parsed.copy(path = path)
}

fun parseNativeAgentSourceFile(path: Path): List<NativeAgentSource> =
  if (path.fileName.toString() == NATIVE_AGENT_BUNDLE_FILE) {
    parseNativeAgentBundle(path)
  } else {
    listOf(parseNativeAgentSource(path))
  }

fun parseNativeAgentSourceText(
  text: String,
  label: String = "native agent source",
): NativeAgentSource {
  val normalized = text.replace("\r\n", "\n")
  if (!normalized.startsWith("---\n")) {
    invalidNativeAgentSourceInput("$label: native agent source must start with YAML frontmatter")
  }
  val end = normalized.indexOf("\n---\n", startIndex = FRONTMATTER_OPEN_LENGTH)
  if (end < 0) {
    invalidNativeAgentSourceInput("$label: native agent source frontmatter must close with ---")
  }
  val frontmatterBlock = normalized.substring(FRONTMATTER_OPEN_LENGTH, end)
  val frontmatter = parseSimpleFrontmatter(frontmatterBlock, label)
  val name = frontmatter["name"].orEmpty()
  val description = frontmatter["description"].orEmpty()
  val composition = parseCompositionDirective(frontmatter["compose"], label)
  val tools = parseNativeAgentTools(frontmatter["tools"]?.let { decodeFlowSequence(it, label) }, label)
  if (!name.matches(Regex("^[a-z][a-z0-9-]*$"))) {
    invalidNativeAgentSourceInput("$label: native agent name must be lowercase kebab-case")
  }
  if (description.isBlank()) {
    invalidNativeAgentSourceInput("$label: native agent description is required")
  }
  val body = normalized.substring(end + "\n---\n".length).removePrefix("\n").trimEnd()
  if (body.isBlank() && composition == null) {
    invalidNativeAgentSourceInput("$label: native agent body is required")
  }

  validateNativeAgentSourceNode(frontmatter, tools, body, label)
  return NativeAgentSource(
    name = name,
    description = description,
    body = body,
    composition = composition,
    tools = tools,
  )
}

private fun decodeFlowSequence(
  value: String,
  label: String,
): List<String> {
  val trimmed = value.trim()
  if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
    invalidNativeAgentSourceInput("$label: native agent frontmatter 'tools' must use the inline form [A, B]")
  }
  return trimmed.substring(1, trimmed.length - 1)
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
}

fun renderNativeAgentSource(agent: NativeAgentSource): String =
  buildString {
    append("---").append('\n')
    append("contract_version: \"").append(NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION).append('"').append('\n')
    append("name: ${agent.name}").append('\n')
    append("description: ${agent.description}").append('\n')
    agent.composition?.let { directive ->
      append("compose: ${directive.kind.wireValue}").append('\n')
    }
    if (agent.tools.isNotEmpty()) {
      append("tools: [").append(agent.tools.joinToString(", ")).append(']').append('\n')
    }
    append("---").append('\n')
    append('\n')
    val body = agent.body.trimEnd()
    if (body.isNotEmpty()) {
      append(body).append('\n')
    }
  }

private fun parseSimpleFrontmatter(
  raw: String,
  label: String,
): Map<String, String> {
  val parsed = linkedMapOf<String, String>()
  raw.lineSequence().filter { it.isNotBlank() }.forEach { line ->
    val separator = line.indexOf(':')
    if (separator <= 0) {
      invalidNativeAgentSourceInput("$label: native agent frontmatter line must use key: value syntax")
    }
    val key = line.substring(0, separator).trim()
    val value = decodeYamlScalar(line.substring(separator + 1).trimStart(), label)

    if (key !in setOf("name", "description", "compose", "contract_version", "tools")) {
      invalidNativeAgentSourceInput("$label: unsupported native agent frontmatter key '$key'")
    }
    parsed[key] = value
  }
  return parsed
}

private val DOUBLE_QUOTE_DECODE_MAP: Map<String, String> =
  YAML_DOUBLE_QUOTE_ESCAPES.entries.associate { (decoded, escape) -> escape to decoded.toString() }

private fun decodeYamlScalar(
  value: String,
  label: String,
): String {
  if (value.isEmpty()) {
    return value
  }
  return when (value.first()) {
    '"' -> {
      if (value.length < 2 || !value.endsWith('"')) {
        invalidNativeAgentSourceInput("$label: native agent frontmatter has unterminated double-quoted scalar")
      }
      val inner = value.substring(1, value.length - 1)

      var trailingBackslashes = 0
      var probe = inner.length - 1
      while (probe >= 0 && inner[probe] == '\\') {
        trailingBackslashes += 1
        probe -= 1
      }
      if (trailingBackslashes % 2 != 0) {
        invalidNativeAgentSourceInput("$label: native agent frontmatter has unterminated double-quoted scalar")
      }
      decodeYamlDoubleQuoted(inner, label)
    }
    '\'' -> {
      if (value.length < 2 || !value.endsWith('\'')) {
        invalidNativeAgentSourceInput("$label: native agent frontmatter has unterminated single-quoted scalar")
      }
      decodeYamlSingleQuoted(value.substring(1, value.length - 1), label)
    }
    else -> value.trimEnd()
  }
}

private fun decodeYamlDoubleQuoted(
  inner: String,
  label: String,
): String =
  buildString {
    var index = 0
    while (index < inner.length) {
      val char = inner[index]
      if (char == '\\') {
        if (index + 1 >= inner.length) {
          invalidNativeAgentSourceInput("$label: native agent frontmatter has unterminated double-quoted scalar")
        }
        val next = inner[index + 1]
        val decoded = DOUBLE_QUOTE_DECODE_MAP["\\$next"]
        if (decoded == null) {
          invalidNativeAgentSourceInput("$label: native agent frontmatter has unknown escape sequence \\$next")
        }
        append(decoded)
        index += 2
      } else {
        if (char == '"') {
          invalidNativeAgentSourceInput(
            "$label: native agent frontmatter has unescaped double quote inside double-quoted scalar",
          )
        }
        append(char)
        index += 1
      }
    }
  }

private fun decodeYamlSingleQuoted(
  inner: String,
  label: String,
): String =
  buildString {
    var index = 0
    while (index < inner.length) {
      val char = inner[index]
      if (char == '\'') {
        if (index + 1 >= inner.length || inner[index + 1] != '\'') {
          invalidNativeAgentSourceInput(
            "$label: native agent frontmatter has unescaped single quote inside single-quoted scalar",
          )
        }
        append('\'')
        index += 2
      } else {
        append(char)
        index += 1
      }
    }
  }

private fun invalidNativeAgentSourceInput(message: String): Nothing =
  throw SkillBillRuntimeException(InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA, message)

private fun validateNativeAgentSourceNode(
  frontmatter: Map<String, String>,
  tools: List<String>,
  body: String,
  label: String,
) {
  val instance: ObjectNode = JsonNodeFactory.instance.objectNode()
  frontmatter["name"]?.let { instance.put("name", it) }
  frontmatter["description"]?.let { instance.put("description", it) }
  frontmatter["compose"]?.let { instance.put("compose", it) }
  frontmatter[SharedPayloadKeys.CONTRACT_VERSION]?.let { instance.put(SharedPayloadKeys.CONTRACT_VERSION, it) }
  if (tools.isNotEmpty()) {
    instance.putArray("tools").apply { tools.forEach { add(it) } }
  }
  if (body.isNotBlank()) {
    instance.put("body", body)
  }
  NativeAgentCompositionSchemaValidator.validateParsedNode(instance, label)
}
