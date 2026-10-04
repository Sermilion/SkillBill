package skillbill.mcp.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys
import skillbill.error.shellcontent.invalidTelemetryEventSchemaError
import skillbill.mcp.shared.McpProtocolFramer
import skillbill.mcp.telemetry.TelemetryEventSchemaValidator
import java.util.concurrent.ConcurrentHashMap

internal object McpInputSchemaProjection {
  private val mapper: ObjectMapper = ObjectMapper()
  private val cache: ConcurrentHashMap<String, Map<String, Any?>> = ConcurrentHashMap()

  fun projectedInputSchema(tool: McpTool): Map<String, Any?> = cache.getOrPut(tool.name) { project(tool) }

  private fun project(tool: McpTool): Map<String, Any?> {
    val defs =
      TelemetryEventSchemaValidator.canonicalSchemaDocument()
        .path(McpProtocolFramer.SCHEMA_DEFS_KEY)
    if (!defs.isObject) {
      throw invalidTelemetryEventSchemaError(
        fieldPath = McpProtocolFramer.SCHEMA_DEFS_KEY,
        eventName = tool.name,
        reason = "Canonical telemetry-event schema is missing a \$defs object.",
      )
    }
    val inlined = inlineLocalRefs(branchForTool(defs, tool.name), defs)
    val stripped = stripAdvertisementEnvelope(inlined, tool)
    return jsonObjectToMap(stripRuntimeOnlyEnumValues(stripped, tool))
  }

  private fun branchForTool(
    defs: JsonNode,
    toolName: String,
  ): JsonNode {
    defs.fields().forEach { (_, defNode) ->
      val eventName =
        defNode.path(McpProtocolFramer.SCHEMA_PROPERTIES_KEY)
          .path(LifecycleTelemetryPayloadKeys.EVENT_NAME)
          .path(McpProtocolFramer.SCHEMA_CONST_KEY)
          .asText("")
      if (eventName == toolName) {
        return defNode
      }
    }
    throw invalidTelemetryEventSchemaError(
      fieldPath = LifecycleTelemetryPayloadKeys.EVENT_NAME,
      eventName = toolName,
      reason = "No \$defs branch pins event_name.const to '$toolName'.",
    )
  }

  private fun stripAdvertisementEnvelope(
    branch: JsonNode,
    tool: McpTool,
  ): ObjectNode {
    val copy = branch.deepCopy() as ObjectNode
    val properties = copy.path(McpProtocolFramer.SCHEMA_PROPERTIES_KEY)
    if (!properties.isObject) {
      return copy
    }
    val propertiesCopy = properties.deepCopy() as ObjectNode
    val removedPropertyKeys =
      linkedSetOf(LifecycleTelemetryPayloadKeys.EVENT_NAME, SharedPayloadKeys.CONTRACT_VERSION) +
        tool.runtimeOwnedArgumentKeys
    removedPropertyKeys.forEach(propertiesCopy::remove)
    copy.set<JsonNode>(McpProtocolFramer.SCHEMA_PROPERTIES_KEY, propertiesCopy)

    val required = copy.path(McpProtocolFramer.SCHEMA_REQUIRED_KEY)
    if (required.isArray) {
      val requiredCopy = mapper.createArrayNode()
      required.forEach { element ->
        val key = element.asText("")
        if (key !in removedPropertyKeys) {
          requiredCopy.add(key)
        }
      }
      copy.set<JsonNode>(McpProtocolFramer.SCHEMA_REQUIRED_KEY, requiredCopy)
    }
    return copy
  }

  private fun stripRuntimeOnlyEnumValues(
    branch: ObjectNode,
    tool: McpTool,
  ): ObjectNode {
    val (propertyName, allowedValues) = tool.advertisedEnumSubset ?: return branch
    val properties = branch.path(McpProtocolFramer.SCHEMA_PROPERTIES_KEY)
    val property = properties.path(propertyName)
    val enum = property.path(McpProtocolFramer.SCHEMA_ENUM_KEY)
    if (!properties.isObject || !property.isObject || !enum.isArray) {
      return branch
    }
    val enumCopy = mapper.createArrayNode()
    enum.forEach { value ->
      if (value.asText("") in allowedValues) {
        enumCopy.add(value)
      }
    }
    val propertyCopy = property.deepCopy() as ObjectNode
    propertyCopy.set<JsonNode>(McpProtocolFramer.SCHEMA_ENUM_KEY, enumCopy)
    val propertiesCopy = properties.deepCopy() as ObjectNode
    propertiesCopy.set<JsonNode>(propertyName, propertyCopy)
    branch.set<JsonNode>(McpProtocolFramer.SCHEMA_PROPERTIES_KEY, propertiesCopy)
    return branch
  }

  private fun inlineLocalRefs(
    node: JsonNode,
    defs: JsonNode,
  ): JsonNode {
    if (node.isObject) {
      val ref = node.path(McpProtocolFramer.SCHEMA_REF_KEY)
      if (!ref.isMissingNode && ref.isTextual) {
        val refText = ref.asText("")
        val defsPrefix = "#/${McpProtocolFramer.SCHEMA_DEFS_KEY}/"
        if (refText.startsWith(defsPrefix)) {
          val defName = refText.removePrefix(defsPrefix)
          val resolved = defs.path(defName)
          if (!resolved.isMissingNode) {
            return inlineLocalRefs(resolved.deepCopy(), defs)
          }
        }
      }
      val copy = node.deepCopy() as ObjectNode
      val fieldNames = copy.fieldNames().asSequence().toList()
      fieldNames.forEach { fieldName ->
        copy.set<JsonNode>(fieldName, inlineLocalRefs(copy.get(fieldName), defs))
      }
      return copy
    }
    if (node.isArray) {
      val copy = mapper.createArrayNode()
      node.forEach { element -> copy.add(inlineLocalRefs(element, defs)) }
      return copy
    }
    return node
  }

  private fun jsonObjectToMap(node: ObjectNode): Map<String, Any?> =
    JsonCodec.anyToStringAnyMap(mapper.convertValue(node, Any::class.java))
      ?: emptyMap()
}
