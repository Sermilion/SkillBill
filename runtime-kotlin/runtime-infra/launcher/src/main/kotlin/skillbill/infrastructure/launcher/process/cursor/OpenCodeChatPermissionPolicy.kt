package skillbill.infrastructure.launcher.process.cursor

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.ObjectNode

data class OpenCodeChatPermissionOverlay(
  val inlineConfiguration: String,
  val permissionEnvironment: String,
)

object OpenCodeChatPermissionPolicy {
  const val PERMISSION_ENVIRONMENT = "OPENCODE_PERMISSION"
  private const val WILDCARD = OpenCodeChatPermissionKeys.WILDCARD
  private val safePermissions = setOf("read", "glob", "grep", "list", "webfetch", "websearch")
  private val safeControlPermissions = setOf("question", "plan_enter", "plan_exit", "doom_loop")
  private val mapper: ObjectMapper =
    JsonMapper.builder()
      .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
      .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
      .build()

  fun prepare(
    resolvedConfiguration: String,
    originalInlineConfiguration: String?,
    readOnly: Boolean,
    baselineAgentConfigurations: Map<String, String>,
  ): OpenCodeChatPermissionOverlay? {
    val resolved = parseObject(resolvedConfiguration) ?: return null
    val original = inlineConfiguration(originalInlineConfiguration) ?: return null
    val globalDenials = denials(resolved.get(OpenCodeChatPermissionKeys.PERMISSION))
    val rootPolicy = policy(resolved.get(OpenCodeChatPermissionKeys.PERMISSION), readOnly, globalDenials)
    original.set<ObjectNode>(OpenCodeChatPermissionKeys.PERMISSION, rootPolicy)
    if (!prepareAgents(original, resolved, baselineAgentConfigurations, readOnly)) return null
    return OpenCodeChatPermissionOverlay(
      mapper.writeValueAsString(original),
      mapper.writeValueAsString(rootPolicy),
    )
  }

  private fun prepareAgents(
    original: ObjectNode,
    resolved: ObjectNode,
    baselineAgentConfigurations: Map<String, String>,
    readOnly: Boolean,
  ): Boolean {
    val agentNames = agentNames(resolved).plus(baselineAgentConfigurations.keys)
    if (!baselineAgentConfigurations.keys.containsAll(agentNames)) return false
    val agentOverlay = original.withObject(OpenCodeChatPermissionKeys.AGENT)
    val modeOverlay = original.withObject(OpenCodeChatPermissionKeys.MODE)
    agentNames.forEach { name ->
      val overlay = if (name in legacyModeNames(resolved)) modeOverlay else agentOverlay
      val agent = agentOverlay(baselineAgentConfigurations.getValue(name), overlay.get(name), readOnly) ?: return false
      overlay.set<ObjectNode>(name, agent)
    }
    return true
  }

  private fun inlineConfiguration(source: String?): ObjectNode? =
    if (source == null) mapper.createObjectNode() else parseObject(source)

  private fun agentOverlay(
    baselineConfiguration: String,
    selected: JsonNode?,
    readOnly: Boolean,
  ): ObjectNode? {
    val baseline = parseObject(baselineConfiguration) ?: return null
    val rules = baseline.get(OpenCodeChatPermissionKeys.PERMISSION)?.let(::permissionConfiguration) ?: return null
    val agent =
      when {
        selected == null -> mapper.createObjectNode()
        selected.isObject -> selected.deepCopy<ObjectNode>()
        else -> return null
      }
    agent.set<ObjectNode>(OpenCodeChatPermissionKeys.PERMISSION, policy(rules, readOnly, denials(rules)))
    return agent
  }

  fun agentNames(
    resolvedConfiguration: String,
    agentListing: String,
  ): Set<String>? {
    val resolved = parseObject(resolvedConfiguration) ?: return null
    val available =
      agentListing.lineSequence().mapNotNull { line ->
        AGENT_LIST_LINE.matchEntire(line.trim())?.groupValues?.get(1)
      }.toSet()
    if (available.isEmpty()) return null
    return agentNames(resolved).plus(available)
  }

  fun admits(
    agentConfiguration: String,
    readOnly: Boolean,
  ): Boolean {
    val agent = parseObject(agentConfiguration) ?: return false
    val rules = agent.get(OpenCodeChatPermissionKeys.PERMISSION)?.let(::permissionRules) ?: return false
    val fallback = rules.lastOrNull { it.first == WILDCARD && it.second == WILDCARD }?.third
    if (fallback != "deny" && (readOnly || fallback != "ask")) return false
    return !permitsMutation(rules)
  }

  private fun permitsMutation(rules: List<Triple<String, String, String>>): Boolean =
    rules.withIndex().any { (index, rule) ->
      val (permissionName, pattern, action) = rule
      val nonMutating =
        permissionName in safePermissions || permissionName in safeControlPermissions ||
          permissionName == OpenCodeChatPermissionKeys.EXTERNAL_DIRECTORY
      if (action != "allow" || nonMutating) {
        false
      } else {
        rules.drop(index + 1).none { later ->
          later.third != "allow" && selectorCovers(later.first, permissionName) && selectorCovers(later.second, pattern)
        }
      }
    }

  private fun permissionRules(source: JsonNode): List<Triple<String, String, String>>? {
    if (!source.isArray || source.isEmpty) return null
    return source.map { permissionRule(it) ?: return null }
  }

  private fun permissionRule(rule: JsonNode): Triple<String, String, String>? {
    val permissionName = rule.path(OpenCodeChatPermissionKeys.PERMISSION).takeIf(JsonNode::isTextual)?.asText()
    val pattern = rule.path(OpenCodeChatPermissionKeys.PATTERN).takeIf(JsonNode::isTextual)?.asText()
    val action = rule.path(OpenCodeChatPermissionKeys.ACTION).takeIf(JsonNode::isTextual)?.asText()
    return if (permissionName != null && pattern != null && action != null) {
      Triple(permissionName, pattern, action)
    } else {
      null
    }
  }

  private fun selectorCovers(
    later: String,
    earlier: String,
  ): Boolean =
    when {
      earlier == WILDCARD -> later == WILDCARD
      later == WILDCARD || later == earlier -> true
      else -> false
    }

  private fun policy(
    source: JsonNode?,
    readOnly: Boolean,
    deniedRules: Set<Pair<String, String>>,
  ): ObjectNode {
    val result = mapper.createObjectNode()
    val fallback = if (readOnly || (WILDCARD to WILDCARD) in deniedRules) "deny" else "ask"
    val wildcardRule = mapper.createObjectNode().put(WILDCARD, fallback)
    result.set<ObjectNode>(WILDCARD, wildcardRule)
    if (source?.isObject == true) {
      val sourceWildcard = source.get(WILDCARD)
      if (sourceWildcard?.isObject == true) {
        sourceWildcard.fields().forEachRemaining { (pattern, configuredAction) ->
          if (pattern == WILDCARD || !configuredAction.isTextual) return@forEachRemaining
          wildcardRule.put(pattern, if (configuredAction.asText() == "deny") "deny" else fallback)
        }
      }
      source.fields().forEachRemaining { (permissionName, value) ->
        if (permissionName == WILDCARD) return@forEachRemaining
        result.set<JsonNode>(permissionName, policyRule(permissionName, value, readOnly, deniedRules))
      }
    }
    safePermissions.forEach { permissionName ->
      if (result.get(permissionName) == null && readOnly && !isDenied(permissionName, WILDCARD, deniedRules)) {
        result.put(permissionName, "allow")
      }
    }
    return result
  }

  private fun policyRule(
    permissionName: String,
    value: JsonNode,
    readOnly: Boolean,
    deniedRules: Set<Pair<String, String>>,
  ): JsonNode =
    when {
      value.isTextual ->
        mapper.nodeFactory.textNode(
          action(permissionName, WILDCARD, value.asText(), readOnly, deniedRules),
        )
      value.isObject ->
        mapper.createObjectNode().also { result ->
          result.put(
            WILDCARD,
            action(
              permissionName,
              WILDCARD,
              value.get(WILDCARD)?.takeIf(JsonNode::isTextual)?.asText()
                ?: if (readOnly && permissionName in safePermissions) "allow" else "ask",
              readOnly,
              deniedRules,
            ),
          )
          value.fields().forEachRemaining { (pattern, configuredAction) ->
            if (pattern == WILDCARD) return@forEachRemaining
            if (!configuredAction.isTextual) return@forEachRemaining
            result.put(pattern, action(permissionName, pattern, configuredAction.asText(), readOnly, deniedRules))
          }
        }
      else -> mapper.nodeFactory.textNode(if (readOnly) "deny" else "ask")
    }

  private fun action(
    permissionName: String,
    pattern: String,
    configuredAction: String,
    readOnly: Boolean,
    deniedRules: Set<Pair<String, String>>,
  ): String =
    when {
      configuredAction == "deny" || isDenied(permissionName, pattern, deniedRules) -> "deny"
      !readOnly -> "ask"
      permissionName in safePermissions && configuredAction == "allow" -> "allow"
      permissionName in safeControlPermissions && configuredAction == "allow" -> "allow"
      else -> "deny"
    }

  private fun isDenied(
    permissionName: String,
    pattern: String,
    deniedRules: Set<Pair<String, String>>,
  ): Boolean =
    deniedRules.any { (deniedPermission, deniedPattern) ->
      (deniedPermission == permissionName || deniedPermission == WILDCARD) &&
        (deniedPattern == pattern || deniedPattern == WILDCARD)
    }

  private fun denials(source: JsonNode?): Set<Pair<String, String>> =
    buildSet {
      if (source?.isObject != true) return@buildSet
      source.fields().forEachRemaining { (permissionName, value) ->
        when {
          value.isTextual && value.asText() == "deny" -> add(permissionName to WILDCARD)
          value.isObject ->
            value.fields().forEachRemaining { (pattern, configuredAction) ->
              if (configuredAction.asText() == "deny") add(permissionName to pattern)
            }
        }
      }
    }

  private fun permissionConfiguration(rules: JsonNode): ObjectNode? {
    val parsed = permissionRules(rules) ?: return null
    val result = mapper.createObjectNode()
    parsed.forEach { (permissionName, pattern, action) ->
      if (action !in setOf("allow", "ask", "deny")) return null
      val current = result.get(permissionName)
      if (current is ObjectNode) {
        current.put(pattern, action)
      } else {
        result.set<ObjectNode>(permissionName, mapper.createObjectNode().put(pattern, action))
      }
    }
    result.fields().forEachRemaining { (permissionName, patterns) ->
      if (patterns.size() == 1 && patterns.has(WILDCARD)) {
        result.set<JsonNode>(permissionName, patterns.get(WILDCARD))
      }
    }
    return result
  }

  private fun agentNames(configuration: JsonNode): Set<String> =
    (
      configuration.get(OpenCodeChatPermissionKeys.AGENT)?.takeIf(JsonNode::isObject)?.fields()?.asSequence()
        ?.filterNot { it.value.path(OpenCodeChatPermissionKeys.DISABLE).asBoolean(false) }
        ?.map { it.key }?.toSet().orEmpty() +
        legacyModeNames(configuration)
    )

  private fun legacyModeNames(configuration: JsonNode): Set<String> =
    configuration.get(OpenCodeChatPermissionKeys.MODE)?.fieldNames()?.asSequence()?.toSet().orEmpty()

  private fun parseObject(source: String): ObjectNode? =
    try {
      mapper.readTree(source)?.takeIf(JsonNode::isObject) as? ObjectNode
    } catch (_: JsonProcessingException) {
      null
    }

  private val AGENT_LIST_LINE = Regex("^(.+) \\((primary|subagent|all)\\)$")
}

private object OpenCodeChatPermissionKeys {
  const val ACTION = "action"
  const val AGENT = "agent"
  const val DISABLE = "disable"
  const val EXTERNAL_DIRECTORY = "external_directory"
  const val MODE = "mode"
  const val PATTERN = "pattern"
  const val PERMISSION = "permission"
  const val WILDCARD = "*"
}
