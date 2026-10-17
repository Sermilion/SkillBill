package skillbill.infrastructure.workflow.monitor

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import skillbill.contracts.workflow.identity.task.STANDALONE_MONITOR_OWNERSHIP_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.monitor.model.MonitorOutcomeKind
import skillbill.ports.monitor.model.MonitorOwnershipState
import skillbill.ports.monitor.model.MonitorPrimaryOutcome
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import skillbill.ports.monitor.model.MonitorRetentionReason
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import java.time.Instant
import java.time.format.DateTimeParseException
import skillbill.infrastructure.workflow.monitor.StandaloneMonitorOwnershipKeys as Keys

private const val MIN_OWNER_TOKEN_LENGTH = 16
private val OBJECT_ID = Regex("^[0-9a-f]{40,64}$")

internal data class MonitorRetentionRecord(
  val reason: MonitorRetentionReason,
  val detail: String,
  val path: String?,
)

internal data class MonitorOwnershipRecord(
  val target: MonitorPullRequestIdentity,
  val invocationId: String,
  val ownerToken: String,
  val generation: Long,
  val process: FeatureTaskRuntimeProcessIdentity,
  val callerRoot: String,
  val primaryRoot: String,
  val commonDir: String,
  val state: MonitorOwnershipState,
  val executionPath: String?,
  val executionBranch: String?,
  val ownedRefs: List<String>,
  val pinnedHeadSha: String?,
  val pinnedBaseSha: String?,
  val lastPublishedSha: String?,
  val acquiredAt: Instant,
  val updatedAt: Instant,
  val retention: MonitorRetentionRecord?,
  val outcome: MonitorPrimaryOutcome? = null,
)

internal object StandaloneMonitorOwnershipCodec {
  private val mapper = ObjectMapper()

  fun encode(record: MonitorOwnershipRecord): String {
    val root = mapper.createObjectNode()
    root.put(Keys.CONTRACT_VERSION, STANDALONE_MONITOR_OWNERSHIP_CONTRACT_VERSION)
    root.set<ObjectNode>(Keys.TARGET, targetNode(record.target))
    root.put(Keys.INVOCATION_ID, record.invocationId)
    root.put(Keys.OWNER_TOKEN, record.ownerToken)
    root.put(Keys.GENERATION, record.generation)
    root.set<ObjectNode>(Keys.PROCESS, processNode(record.process))
    root.put(Keys.CALLER_ROOT, record.callerRoot)
    root.put(Keys.PRIMARY_ROOT, record.primaryRoot)
    root.put(Keys.COMMON_DIR, record.commonDir)
    root.put(Keys.STATE, record.state.wireValue)
    record.executionPath?.let { root.put(Keys.EXECUTION_PATH, it) }
    record.executionBranch?.let { root.put(Keys.EXECUTION_BRANCH, it) }
    if (record.ownedRefs.isNotEmpty()) {
      val refs = root.putArray(Keys.OWNED_REFS)
      record.ownedRefs.forEach { refs.add(it) }
    }
    record.pinnedHeadSha?.let { root.put(Keys.PINNED_HEAD_SHA, it) }
    record.pinnedBaseSha?.let { root.put(Keys.PINNED_BASE_SHA, it) }
    record.lastPublishedSha?.let { root.put(Keys.LAST_PUBLISHED_SHA, it) }
    root.put(Keys.ACQUIRED_AT, record.acquiredAt.toString())
    root.put(Keys.UPDATED_AT, record.updatedAt.toString())
    record.retention?.let { root.set<ObjectNode>(Keys.RETENTION, retentionNode(it)) }
    record.outcome?.let { root.set<ObjectNode>(Keys.OUTCOME, outcomeNode(it)) }
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root)
  }

  fun decode(
    source: String,
    sourceLabel: String,
  ): MonitorOwnershipRecord {
    val root =
      try {
        mapper.readTree(source)
      } catch (failure: JsonProcessingException) {
        throw invalid(sourceLabel, "the file is not valid JSON", failure)
      }
    if (root == null || !root.isObject) throw invalid(sourceLabel, "the root is not an object")
    requireKnownKeys(root, Keys.TOP_LEVEL, sourceLabel, "<root>")
    requireVersion(root, sourceLabel)
    val record = recordOf(root, sourceLabel)
    checkCoherence(record, sourceLabel)
    return record
  }

  private fun recordOf(
    root: JsonNode,
    label: String,
  ): MonitorOwnershipRecord {
    val reader = FieldReader(label)
    val state = MonitorOwnershipState.fromWire(reader.text(root, Keys.STATE)) ?: throw invalid(label, "unknown state")
    val ownerToken = reader.text(root, Keys.OWNER_TOKEN)
    if (ownerToken.length < MIN_OWNER_TOKEN_LENGTH) throw invalid(label, "owner_token is too short")
    return MonitorOwnershipRecord(
      target = targetOf(root.path(Keys.TARGET), reader, label),
      invocationId = reader.text(root, Keys.INVOCATION_ID),
      ownerToken = ownerToken,
      generation = reader.positiveLong(root, Keys.GENERATION),
      process = processOf(root.path(Keys.PROCESS), reader, label),
      callerRoot = reader.text(root, Keys.CALLER_ROOT),
      primaryRoot = reader.text(root, Keys.PRIMARY_ROOT),
      commonDir = reader.text(root, Keys.COMMON_DIR),
      state = state,
      executionPath = reader.optionalText(root, Keys.EXECUTION_PATH),
      executionBranch = reader.optionalText(root, Keys.EXECUTION_BRANCH),
      ownedRefs = reader.textList(root, Keys.OWNED_REFS),
      pinnedHeadSha = reader.optionalObjectId(root, Keys.PINNED_HEAD_SHA),
      pinnedBaseSha = reader.optionalObjectId(root, Keys.PINNED_BASE_SHA),
      lastPublishedSha = reader.optionalObjectId(root, Keys.LAST_PUBLISHED_SHA),
      acquiredAt = reader.instant(root, Keys.ACQUIRED_AT),
      updatedAt = reader.instant(root, Keys.UPDATED_AT),
      retention = retentionOf(root.path(Keys.RETENTION), reader, label),
      outcome = outcomeOf(root.path(Keys.OUTCOME), reader, label),
    )
  }

  private fun targetOf(
    node: JsonNode,
    reader: FieldReader,
    label: String,
  ): MonitorPullRequestIdentity {
    requireObject(node, Keys.TARGET, label)
    requireKnownKeys(node, Keys.TARGET_FIELDS, label, Keys.TARGET)
    val slug = reader.text(node, Keys.TARGET_REPOSITORY).split('/')
    if (slug.size != 2 || slug.any(String::isBlank)) throw invalid(label, "target.repository is not owner/name")
    val identity =
      MonitorPullRequestIdentity(
        MonitorRepositoryIdentity.canonical(reader.text(node, Keys.TARGET_HOST), slug[0], slug[1]),
        reader.positiveLong(node, Keys.TARGET_NUMBER).toInt(),
      )
    if (reader.text(node, Keys.TARGET_URL) != identity.url) throw invalid(label, "target.url does not match the target")
    return identity
  }

  private fun processOf(
    node: JsonNode,
    reader: FieldReader,
    label: String,
  ): FeatureTaskRuntimeProcessIdentity {
    requireObject(node, Keys.PROCESS, label)
    requireKnownKeys(node, Keys.PROCESS_FIELDS, label, Keys.PROCESS)
    return FeatureTaskRuntimeProcessIdentity(
      hostIdentity = reader.text(node, Keys.HOST_IDENTITY),
      bootIdentity = reader.text(node, Keys.BOOT_IDENTITY),
      pid = reader.positiveLong(node, Keys.PID),
      processBirthToken = reader.text(node, Keys.PROCESS_BIRTH_TOKEN),
    )
  }

  private fun retentionOf(
    node: JsonNode,
    reader: FieldReader,
    label: String,
  ): MonitorRetentionRecord? {
    if (node.isMissingNode) return null
    requireObject(node, Keys.RETENTION, label)
    requireKnownKeys(node, Keys.RETENTION_FIELDS, label, Keys.RETENTION)
    val reason =
      MonitorRetentionReason.fromWire(reader.text(node, Keys.RETENTION_REASON))
        ?: throw invalid(label, "unknown retention reason")
    return MonitorRetentionRecord(
      reason,
      reader.text(node, Keys.RETENTION_DETAIL),
      reader.optionalText(node, Keys.RETENTION_PATH),
    )
  }

  private fun outcomeOf(
    node: JsonNode,
    reader: FieldReader,
    label: String,
  ): MonitorPrimaryOutcome? {
    if (node.isMissingNode) return null
    requireObject(node, Keys.OUTCOME, label)
    requireKnownKeys(node, Keys.OUTCOME_FIELDS, label, Keys.OUTCOME)
    val kind =
      MonitorOutcomeKind.fromWire(reader.text(node, Keys.OUTCOME_KIND))
        ?: throw invalid(label, "unknown outcome kind")
    return MonitorPrimaryOutcome(kind, reader.text(node, Keys.OUTCOME_DETAIL))
  }

  private fun checkCoherence(
    record: MonitorOwnershipRecord,
    label: String,
  ) {
    val prepared =
      listOf(
        record.executionPath,
        record.executionBranch,
        record.pinnedHeadSha,
        record.pinnedBaseSha,
        record.lastPublishedSha,
      )
    val problem =
      when {
        record.updatedAt.isBefore(record.acquiredAt) -> "updated_at is earlier than acquired_at"
        record.state == MonitorOwnershipState.PREPARED && prepared.any { it == null } ->
          "a prepared record needs the execution path, branch, and every pinned and published SHA"
        record.outcome != null &&
          record.state in setOf(MonitorOwnershipState.ACQUIRING, MonitorOwnershipState.PREPARED) ->
          "an acquiring or prepared record must not carry an outcome"
        record.state == MonitorOwnershipState.RETAINED && record.retention == null ->
          "a retained record needs a retention reason"
        record.state != MonitorOwnershipState.RETAINED && record.retention != null ->
          "only a retained record may carry a retention reason"
        record.state == MonitorOwnershipState.RELEASED &&
          (record.executionPath != null || record.executionBranch != null || record.ownedRefs.isNotEmpty()) ->
          "a released tombstone must not name execution resources"
        else -> null
      }
    if (problem != null) {
      throw SkillBillRuntimeException(
        StandaloneMonitorFailureCode.INCOHERENT_OWNERSHIP,
        "Standalone monitor ownership '$label' is incoherent: $problem.",
      )
    }
  }

  private fun requireVersion(
    root: JsonNode,
    label: String,
  ) {
    val version = root.path(Keys.CONTRACT_VERSION).takeIf(JsonNode::isTextual)?.asText().orEmpty()
    if (version != STANDALONE_MONITOR_OWNERSHIP_CONTRACT_VERSION) {
      throw SkillBillRuntimeException(
        StandaloneMonitorFailureCode.UNSUPPORTED_OWNERSHIP_VERSION,
        "Standalone monitor ownership '$label' uses contract version '${version.ifBlank { "<absent>" }}'; " +
          "this runtime reads '$STANDALONE_MONITOR_OWNERSHIP_CONTRACT_VERSION'.",
      )
    }
  }

  private fun requireObject(
    node: JsonNode,
    field: String,
    label: String,
  ) {
    if (!node.isObject) throw invalid(label, "'$field' is missing or not an object")
  }

  private fun requireKnownKeys(
    node: JsonNode,
    allowed: Set<String>,
    label: String,
    path: String,
  ) {
    val unknown = node.fieldNames().asSequence().filterNot(allowed::contains).toList()
    if (unknown.isNotEmpty()) throw invalid(label, "'$path' has unknown keys $unknown")
  }

  private fun targetNode(target: MonitorPullRequestIdentity): ObjectNode =
    mapper.createObjectNode().apply {
      put(Keys.TARGET_HOST, target.repository.host)
      put(Keys.TARGET_REPOSITORY, target.repository.slug)
      put(Keys.TARGET_NUMBER, target.number)
      put(Keys.TARGET_URL, target.url)
    }

  private fun processNode(process: FeatureTaskRuntimeProcessIdentity): ObjectNode =
    mapper.createObjectNode().apply {
      put(Keys.HOST_IDENTITY, process.hostIdentity)
      put(Keys.BOOT_IDENTITY, process.bootIdentity)
      put(Keys.PID, process.pid)
      put(Keys.PROCESS_BIRTH_TOKEN, process.processBirthToken)
    }

  private fun retentionNode(retention: MonitorRetentionRecord): ObjectNode =
    mapper.createObjectNode().apply {
      put(Keys.RETENTION_REASON, retention.reason.wireValue)
      put(Keys.RETENTION_DETAIL, retention.detail)
      retention.path?.let { put(Keys.RETENTION_PATH, it) }
    }

  private fun outcomeNode(outcome: MonitorPrimaryOutcome): ObjectNode =
    mapper.createObjectNode().apply {
      put(Keys.OUTCOME_KIND, outcome.kind.wireValue)
      put(Keys.OUTCOME_DETAIL, outcome.detail)
    }

  private class FieldReader(private val label: String) {
    fun text(
      node: JsonNode,
      field: String,
    ): String = optionalText(node, field) ?: throw invalid(label, "'$field' is missing or blank")

    fun optionalText(
      node: JsonNode,
      field: String,
    ): String? {
      val value = node.path(field)
      if (value.isMissingNode) return null
      if (!value.isTextual || value.asText().isBlank()) throw invalid(label, "'$field' is not a non-blank string")
      return value.asText()
    }

    fun optionalObjectId(
      node: JsonNode,
      field: String,
    ): String? =
      optionalText(node, field)?.also {
        if (!OBJECT_ID.matches(it)) throw invalid(label, "'$field' is not an object id")
      }

    fun positiveLong(
      node: JsonNode,
      field: String,
    ): Long {
      val value = node.path(field)
      if (!value.isIntegralNumber || value.asLong() < 1) throw invalid(label, "'$field' is not a positive integer")
      return value.asLong()
    }

    fun textList(
      node: JsonNode,
      field: String,
    ): List<String> {
      val value = node.path(field)
      if (value.isMissingNode) return emptyList()
      if (!value.isArray) throw invalid(label, "'$field' is not an array")
      val items = value.map { item -> item.takeIf { it.isTextual && it.asText().isNotBlank() }?.asText() }
      if (items.any { it == null } || items.toSet().size != items.size) throw invalid(label, "'$field' is malformed")
      return items.filterNotNull()
    }

    fun instant(
      node: JsonNode,
      field: String,
    ): Instant =
      try {
        Instant.parse(text(node, field))
      } catch (failure: DateTimeParseException) {
        throw invalid(label, "'$field' is not a timestamp", failure)
      }
  }

  private fun invalid(
    label: String,
    reason: String,
    cause: Throwable? = null,
  ): SkillBillRuntimeException =
    SkillBillRuntimeException(
      StandaloneMonitorFailureCode.INVALID_OWNERSHIP_SCHEMA,
      "Standalone monitor ownership '$label' fails schema validation: $reason.",
      cause,
    )
}
