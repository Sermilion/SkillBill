package skillbill.infrastructure.skills.nativeagent.composition

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.error.shellcontent.invalidNativeAgentCompositionSchemaError
import skillbill.infrastructure.skills.nativeagent.rendering.YAML_DOUBLE_QUOTE_ESCAPES
import java.nio.file.Files
import java.nio.file.Path

fun parseNativeAgentBundle(path: Path): List<NativeAgentSource> {
  val yamlText = Files.readString(path)
  NativeAgentCompositionSchemaValidator.validate(yamlText, path.toString())
  return try {
    parseValidatedNativeAgentBundle(path, yamlText)
  } catch (error: SkillBillRuntimeException) {
    if (error.code != InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA) throw error
    throw invalidNativeAgentCompositionSchemaError(
      sourceLabel = path.toString(),
      reason = error.message.orEmpty().ifBlank { "native agent bundle is invalid" },
      cause = error,
    )
  }
}

private fun parseValidatedNativeAgentBundle(
  path: Path,
  yamlText: String,
): List<NativeAgentSource> {
  val raw =
    try {
      Yaml().load<Any?>(yamlText)
    } catch (error: YAMLException) {
      invalidBundle("$path: native agent bundle is not valid YAML: ${error.message}", error)
    }
  val root = raw as? Map<*, *> ?: invalidBundle("$path: native agent bundle must be a YAML mapping at the top level")

  requireSupportedKeys(root.keys, setOf("agents", "contract_version")) { key ->
    "$path: unsupported native agent bundle key '$key'"
  }
  val agents =
    root["agents"] as? List<*>
      ?: invalidBundle("$path: native agent bundle field 'agents' must be a list")
  if (agents.isEmpty()) {
    invalidBundle("$path: native agent bundle field 'agents' must not be empty")
  }
  val parsed =
    agents.mapIndexed { index, entry ->
      parseNativeAgentBundleEntry(path, index, entry)
    }
  return parsed
}

private fun parseNativeAgentBundleEntry(
  path: Path,
  index: Int,
  entry: Any?,
): NativeAgentSource {
  val entryLabel = "$path agent[$index]"
  val map = entry as? Map<*, *> ?: invalidBundle("$entryLabel: native agent bundle entry must be a mapping")
  requireSupportedKeys(map.keys, setOf("name", "description", "compose", "body", "tools")) { key ->
    "$entryLabel: unsupported native agent bundle entry key '$key'"
  }
  val name = map.requiredString("name", entryLabel)
  val label = "$path entry '$name'"
  val description = map.requiredString("description", label)
  val composition = parseCompositionDirective(map.optionalString("compose", label), label)
  val body = map.optionalString("body", label).orEmpty().trimEnd()
  if (!name.matches(Regex("^[a-z][a-z0-9-]*$"))) {
    invalidBundle("$label: native agent name must be lowercase kebab-case")
  }
  if (description.isBlank()) {
    invalidBundle("$label: native agent description is required")
  }
  if (body.isBlank() && composition == null) {
    invalidBundle("$label: native agent body is required")
  }
  return NativeAgentSource(
    name = name,
    description = description,
    body = body,
    composition = composition,
    path = path,
    bundleEntryName = name,
    tools = parseNativeAgentTools(map["tools"], label),
  )
}

internal fun parseNativeAgentTools(
  raw: Any?,
  label: String,
): List<String> {
  if (raw == null) {
    return emptyList()
  }
  val entries =
    raw as? List<*> ?: invalidBundle("$label: native agent 'tools' must be a list of tool names")
  if (entries.isEmpty()) {
    invalidBundle("$label: native agent 'tools' must not be empty; omit the key to inherit every host tool")
  }
  val names =
    entries.map { entry ->
      val name = (entry as? String)?.trim().orEmpty()
      if (name.isEmpty()) {
        invalidBundle("$label: native agent 'tools' entries must be non-empty tool names")
      }
      name
    }
  if (names.distinct().size != names.size) {
    invalidBundle("$label: native agent 'tools' must not repeat a tool name")
  }
  return names
}

fun renderNativeAgentBundle(agents: List<NativeAgentSource>): String =
  buildString {
    append("contract_version: \"").append(NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION).append('"').append('\n')
    append("agents:").append('\n')
    agents.forEach { agent ->
      append("  - name: ${agent.name}").append('\n')
      append("    description: ${nativeAgentYamlDoubleQuotedScalar(agent.description)}").append('\n')
      agent.composition?.let { directive ->
        append("    compose: ${directive.kind.wireValue}").append('\n')
      }
      if (agent.tools.isNotEmpty()) {
        append("    tools: [").append(agent.tools.joinToString(", ")).append(']').append('\n')
      }
      val body = agent.body.trimEnd()
      if (body.isNotEmpty()) {
        append("    body: |-").append('\n')
        body.lineSequence().forEach { line ->
          append("      ").append(line).append('\n')
        }
      }
    }
  }

private fun requireSupportedKeys(
  keys: Set<Any?>,
  supported: Set<String>,
  message: (Any?) -> String,
) {
  val unsupported = keys.firstOrNull { it !in supported }
  if (unsupported != null) {
    invalidBundle(message(unsupported))
  }
}

private fun Map<*, *>.requiredString(
  key: String,
  label: String,
): String = this[key] as? String ?: invalidBundle("$label: native agent bundle entry field '$key' must be a string")

private fun Map<*, *>.optionalString(
  key: String,
  label: String,
): String? {
  val value = this[key] ?: return null
  return value as? String ?: invalidBundle("$label: native agent bundle entry field '$key' must be a string")
}

private fun nativeAgentYamlDoubleQuotedScalar(value: String): String =
  "\"" + value.map { char -> YAML_DOUBLE_QUOTE_ESCAPES[char] ?: char.toString() }.joinToString("") + "\""

private fun invalidBundle(
  message: String,
  cause: Throwable? = null,
): Nothing =
  throw SkillBillRuntimeException(
    InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA,
    message,
    cause,
  )
