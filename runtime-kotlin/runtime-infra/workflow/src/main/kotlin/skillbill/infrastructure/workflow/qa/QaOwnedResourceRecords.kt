package skillbill.infrastructure.workflow.qa

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.contracts.qa.QA_OWNED_RESOURCE_CONTRACT_VERSION
import skillbill.error.featuretask.qa.qaOwnedResourceFailed
import skillbill.ports.qa.model.QaOwnedResource
import skillbill.ports.qa.model.QaOwnedResourceKind
import java.time.Instant
import java.time.format.DateTimeParseException
import skillbill.contracts.qa.QaDeviceLeasePayloadKeys as LeaseKeys
import skillbill.contracts.qa.QaOwnedResourcePayloadKeys as Keys

internal data class QaOwnedResourceRecord(
  val invocationId: String,
  val resource: QaOwnedResource,
  val registeredAt: Instant,
  val processStartedAt: Instant?,
)

internal object QaOwnedResourceRecordCodec {
  private val mapper = ObjectMapper()

  fun encode(record: QaOwnedResourceRecord): String {
    val root = mapper.createObjectNode()
    root.put(Keys.CONTRACT_VERSION, QA_OWNED_RESOURCE_CONTRACT_VERSION)
    root.put(LeaseKeys.INVOCATION_ID, record.invocationId)
    root.put(Keys.KIND, record.resource.kind.wireValue)
    root.put(Keys.IDENTITY, record.resource.identity)
    root.put(Keys.REGISTERED_AT, record.registeredAt.toString())
    record.processStartedAt?.let { root.put(Keys.PROCESS_STARTED_AT, it.toString()) }
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root)
  }

  fun decode(
    source: String,
    label: String,
  ): QaOwnedResourceRecord {
    val root =
      try {
        mapper.readTree(source)
      } catch (failure: JsonProcessingException) {
        fail(label, "the file is not valid JSON", failure)
      }
    if (root == null || !root.isObject) fail(label, "the root is not an object")
    val kind =
      QaOwnedResourceKind.fromWire(text(root, Keys.KIND, label)) ?: fail(label, "kind is not a known resource kind")
    val fields = if (kind == QaOwnedResourceKind.PROCESS) Keys.PROCESS_RECORD_FIELDS else Keys.RECORD_FIELDS
    requireExactKeys(root, fields, label)
    val version = text(root, Keys.CONTRACT_VERSION, label)
    if (version != QA_OWNED_RESOURCE_CONTRACT_VERSION) {
      fail(label, "contract_version '$version' is not $QA_OWNED_RESOURCE_CONTRACT_VERSION")
    }
    val invocationId = text(root, LeaseKeys.INVOCATION_ID, label)
    if (!INVOCATION_ID.matches(invocationId)) fail(label, "invocation_id is malformed")
    return QaOwnedResourceRecord(
      invocationId = invocationId,
      resource = QaOwnedResource(kind, text(root, Keys.IDENTITY, label)),
      registeredAt = instant(root, Keys.REGISTERED_AT, label),
      processStartedAt =
        if (kind == QaOwnedResourceKind.PROCESS) instant(root, Keys.PROCESS_STARTED_AT, label) else null,
    )
  }

  private fun instant(
    node: JsonNode,
    field: String,
    label: String,
  ): Instant =
    try {
      Instant.parse(text(node, field, label))
    } catch (failure: DateTimeParseException) {
      fail(label, "$field is not an instant", failure)
    }

  private fun requireExactKeys(
    node: JsonNode,
    fields: Set<String>,
    label: String,
  ) {
    val names = node.fieldNames().asSequence().toSet()
    val unknown = names - fields
    val missing = fields - names
    if (unknown.isNotEmpty() || missing.isNotEmpty()) {
      fail(label, "unexpected fields $unknown or missing fields $missing")
    }
  }

  private fun text(
    node: JsonNode,
    field: String,
    label: String,
  ): String =
    node.path(field).takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()
      ?: fail(label, "$field is not a non-blank string")

  private fun fail(
    label: String,
    reason: String,
    cause: Throwable? = null,
  ): Nothing = throw qaOwnedResourceFailed("$label is malformed: $reason", cause)
}
