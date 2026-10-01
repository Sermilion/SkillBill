package skillbill.infrastructure.contracts.phaseoutput

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputFormat
import kotlin.coroutines.cancellation.CancellationException

internal data class DuplicateKeyMerge(
  val node: JsonNode,
  val repairedText: String,
  val format: FeatureTaskRuntimePhaseOutputFormat,
  val firstDuplicateOffset: Int,
)

internal object DuplicateKeyMergeParser {
  private val jsonFactory = JsonFactory()
  private val yamlFactory = YAMLFactory()
  private val jsonMapper = ObjectMapper()

  fun merge(
    text: String,
    format: FeatureTaskRuntimePhaseOutputFormat,
  ): DuplicateKeyMerge? =
    try {
      val factory = if (format == FeatureTaskRuntimePhaseOutputFormat.JSON) jsonFactory else yamlFactory
      factory.createParser(text).use { parser ->
        if (parser.nextToken() == null) return@use null
        val tracker = MergeTracker(jsonMapper)
        val node = tracker.parseValue(parser)
        if (parser.nextToken() != null) return@use null
        if (!tracker.changed || !node.isObject) return@use null
        DuplicateKeyMerge(
          node = node,
          repairedText = jsonMapper.writeValueAsString(node),
          format = FeatureTaskRuntimePhaseOutputFormat.JSON,
          firstDuplicateOffset = tracker.firstDuplicateOffset.coerceAtLeast(0),
        )
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: JsonProcessingException) {
      null
    }

  private class MergeTracker(private val mapper: ObjectMapper) {
    var changed: Boolean = false
    var firstDuplicateOffset: Int = -1

    fun parseValue(parser: JsonParser): JsonNode {
      val factory = mapper.nodeFactory
      return when (val token = parser.currentToken()) {
        JsonToken.START_OBJECT -> parseObject(parser)
        JsonToken.START_ARRAY -> parseArray(parser)
        JsonToken.VALUE_STRING -> factory.textNode(parser.text)
        JsonToken.VALUE_NUMBER_INT,
        JsonToken.VALUE_NUMBER_FLOAT,
        -> factory.numberNode(parser.decimalValue)
        JsonToken.VALUE_TRUE -> factory.booleanNode(true)
        JsonToken.VALUE_FALSE -> factory.booleanNode(false)
        JsonToken.VALUE_NULL -> factory.nullNode()
        JsonToken.VALUE_EMBEDDED_OBJECT -> factory.pojoNode(parser.embeddedObject)
        else -> throw JsonParseException(parser, "Unsupported token $token")
      }
    }

    private fun parseObject(parser: JsonParser): ObjectNode {
      val obj = mapper.nodeFactory.objectNode()
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        if (parser.currentToken() != JsonToken.FIELD_NAME) {
          throw JsonParseException(parser, "Expected field name")
        }
        val name = parser.currentName()
        val fieldOffset = tokenOffset(parser)
        parser.nextToken()
        val value = parseValue(parser)
        val existing = obj.get(name)
        if (existing != null) {
          changed = true
          if (firstDuplicateOffset < 0) firstDuplicateOffset = fieldOffset
          obj.replace(name, mergeNodes(existing, value))
        } else {
          obj.replace(name, value)
        }
      }
      return obj
    }

    private fun parseArray(parser: JsonParser): ArrayNode {
      val arr = mapper.nodeFactory.arrayNode()
      while (parser.nextToken() != JsonToken.END_ARRAY) {
        arr.add(parseValue(parser))
      }
      return arr
    }

    private fun mergeNodes(
      first: JsonNode,
      later: JsonNode,
    ): JsonNode {
      if (first == later) return first
      if (first is ObjectNode && later is ObjectNode) {
        val merged = first.deepCopy()
        val fields = later.fields()
        while (fields.hasNext()) {
          val field = fields.next()
          val existing = merged.get(field.key)
          if (existing == null) {
            merged.replace(field.key, field.value)
          } else {
            merged.replace(field.key, mergeNodes(existing, field.value))
          }
        }
        return merged
      }
      if (first is ArrayNode && later is ArrayNode) {
        val merged = first.deepCopy()
        later.forEach { element -> merged.add(element.deepCopy()) }
        return merged
      }
      return first
    }

    private fun tokenOffset(parser: JsonParser): Int {
      val location = parser.currentTokenLocation()
      val charOffset = location.charOffset
      if (charOffset >= 0L) return charOffset.toInt()
      val byteOffset = location.byteOffset
      return if (byteOffset >= 0L) byteOffset.toInt() else 0
    }
  }
}
