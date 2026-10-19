package skillbill.infrastructure.workflow.github

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest

private val digestMapper: ObjectMapper by lazy { ObjectMapper() }

internal fun pullRequestEditableFieldsDigest(node: JsonNode): String {
  val title = node.path("title").takeIf(JsonNode::isTextual)?.asText().orEmpty()
  val body = node.path("body").takeIf(JsonNode::isTextual)?.asText().orEmpty()
  val labels =
    node.path("labels").mapNotNull { label -> label.path("name").takeIf(JsonNode::isTextual)?.asText() }.sorted()
  val fields = digestMapper.writeValueAsString(listOf(title, body, labels))
  return MessageDigest.getInstance("SHA-256")
    .digest(fields.encodeToByteArray())
    .joinToString("") { "%02x".format(it) }
}
