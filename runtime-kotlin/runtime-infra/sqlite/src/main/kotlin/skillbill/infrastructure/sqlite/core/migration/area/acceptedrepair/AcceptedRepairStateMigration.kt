package skillbill.infrastructure.sqlite.core.migration.area.acceptedrepair

import skillbill.contracts.JsonCodec
import skillbill.error.shellcontent.invalidAcceptanceCriterionRepairStateSchema
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.toAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.security.MessageDigest
import java.sql.Connection
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

private const val LEGACY_ACCEPTED_REPAIR_TOKEN = "accepted"
private const val DIGEST_HEX_LENGTH = 64
private const val MAX_CHECKPOINT_LENGTH = 256

internal fun rewriteAcceptedAcceptanceCriterionRepairStates(connection: Connection) {
  val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR
  val pending = connection.acceptedRepairRows(family)
  if (pending.isEmpty()) return
  connection.prepareStatement(
    "UPDATE feature_task_workflows SET artifacts_json = ? WHERE workflow_id = ?",
  ).use { update ->
    pending.forEach { (workflowId, artifactsJson) ->
      update.setString(1, artifactsJson)
      update.setString(2, workflowId)
      val updated = update.executeUpdate()
      if (updated != 1) {
        throw invalidAcceptanceCriterionRepairStateSchema(workflowId, "accepted repair row was not updated")
      }
    }
  }
}

private fun Connection.acceptedRepairRows(family: DurableWorkflowArtifactFamily): List<Pair<String, String>> {
  val modePath = "\$.${family.label()}.${Keys.MODE}"
  prepareStatement(
    """
    SELECT workflow_id, artifacts_json
    FROM feature_task_workflows
    WHERE json_valid(artifacts_json) AND json_extract(artifacts_json, ?) = ?
    """.trimIndent(),
  ).use { select ->
    select.setString(1, modePath)
    select.setString(2, LEGACY_ACCEPTED_REPAIR_TOKEN)
    select.executeQuery().use { rows ->
      val pending = mutableListOf<Pair<String, String>>()
      while (rows.next()) {
        val workflowId = rows.getString(1)
        pending += workflowId to acceptedRepairArtifacts(workflowId, rows.getString(2), family)
      }
      return pending
    }
  }
}

private fun acceptedRepairArtifacts(
  workflowId: String,
  rawArtifacts: String,
  family: DurableWorkflowArtifactFamily,
): String {
  val artifacts = artifactMap(workflowId, rawArtifacts)
  val repair = legacyRepair(workflowId, artifacts, family)
  recordFinalAcceptance(workflowId, repair)
  FeatureTaskRuntimeWorkflowArtifactMap.from(repair).toAcceptanceCriterionRepairState(workflowId)
  family.putInto(artifacts, repair)
  return JsonCodec.mapToJsonString(artifacts)
}

private fun artifactMap(
  workflowId: String,
  rawArtifacts: String,
): MutableMap<String, Any?> {
  val parsed =
    JsonCodec.parseValue(rawArtifacts) as? Map<*, *>
      ?: refuse(workflowId, "workflow artifacts are not an object")
  return stringKeyedMap(parsed, workflowId, "workflow artifacts are not an object")
}

private fun legacyRepair(
  workflowId: String,
  artifacts: MutableMap<String, Any?>,
  family: DurableWorkflowArtifactFamily,
): MutableMap<String, Any?> {
  val repair =
    stringKeyedMap(
      family.value(artifacts) ?: refuse(workflowId, "accepted repair artifact is missing"),
      workflowId,
      "accepted repair artifact is not an object",
    )
  if (repair[Keys.MODE] != LEGACY_ACCEPTED_REPAIR_TOKEN || repair[Keys.STAGE] != LEGACY_ACCEPTED_REPAIR_TOKEN) {
    refuse(workflowId, "legacy accepted repair stage is not accepted")
  }
  val queue = repair[Keys.UNRESOLVED_QUEUE]
  val finished = queue is List<*> && queue.isEmpty() && repair[Keys.CURRENT_CRITERION_REF] == null
  if (!finished || repair[Keys.RESERVATION] != null) {
    refuse(workflowId, "legacy accepted repair is not a finished empty queue")
  }
  val revisionValue = integral(repair[Keys.REVISION])
  if (revisionValue == null || revisionValue !in 1..Long.MAX_VALUE) {
    refuse(workflowId, "legacy accepted repair has no revision for a final audit")
  }
  return repair
}

private fun recordFinalAcceptance(
  workflowId: String,
  repair: MutableMap<String, Any?>,
) {
  val criteria = objectMaps(repair[Keys.CRITERIA], workflowId, "criteria are invalid")
  val (treeDigest, checkpointId) = closureAnchor(repair, workflowId)
  val auditedRefs = auditedRefs(criteria, workflowId)
  val dependencies =
    repair[Keys.DEPENDENCIES] as? List<*> ?: refuse(workflowId, "dependencies are invalid")
  val revision =
    integral(repair[Keys.REVISION])
      ?: refuse(workflowId, "legacy accepted repair has no revision for a final audit")
  val digest =
    acceptanceDigest(
      listOf(
        stringValue(repair, Keys.CATALOG_ID, workflowId),
        stringValue(repair, Keys.EXECUTION_ID, workflowId),
        stringValue(repair, Keys.SPEC_DIGEST, workflowId),
        revision.toString(),
        auditedRefs.joinToString(","),
        treeDigest,
        checkpointId,
      ),
    )
  repair[Keys.MODE] = AcceptanceCriterionRepairMode.FINAL_FULL.wireValue
  repair[Keys.STAGE] = AcceptanceCriterionRepairStage.FINAL_ACCEPTED.wireValue
  repair[Keys.FINAL_AUDIT_REQUIRED] = false
  repair[Keys.FINAL_AUDIT_DIGEST] = digest
  repair[Keys.FINAL_AUDITS] =
    listOf(
      mapOf(
        Keys.REVISION to repair[Keys.REVISION],
        Keys.DIGEST to digest,
        Keys.CATALOG_ID to repair[Keys.CATALOG_ID],
        Keys.EXECUTION_ID to repair[Keys.EXECUTION_ID],
        Keys.SPEC_DIGEST to repair[Keys.SPEC_DIGEST],
        Keys.TREE_DIGEST to treeDigest,
        Keys.CHECKPOINT_ID to checkpointId,
        Keys.AUDITED_REFS to auditedRefs,
        Keys.REGRESSED_REFS to emptyList<String>(),
        Keys.DEPENDENCIES to dependencies,
        Keys.AUDIT_BINDING_DIGEST to null,
      ),
    )
}

private fun closureAnchor(
  repair: Map<String, Any?>,
  workflowId: String,
): Pair<String, String> {
  val transition =
    anchorMaps(repair, Keys.SOURCE_TRANSITIONS, workflowId, "source transitions are invalid")
      .filter { digestAnchor(it[Keys.TREE_DIGEST], it[Keys.CHECKPOINT_ID]) }
      .maxByOrNull { integral(it[Keys.REVISION]) ?: Long.MIN_VALUE }
  if (transition != null) {
    return transition.getValue(Keys.TREE_DIGEST) as String to transition.getValue(Keys.CHECKPOINT_ID) as String
  }
  val criterion =
    anchorMaps(repair, Keys.CRITERIA, workflowId, "criteria are invalid").firstOrNull {
      digestAnchor(it[Keys.CLOSURE_TREE_DIGEST], it[Keys.CLOSURE_CHECKPOINT_ID])
    }
  if (criterion != null) {
    return criterion.getValue(Keys.CLOSURE_TREE_DIGEST) as String to
      criterion.getValue(Keys.CLOSURE_CHECKPOINT_ID) as String
  }
  val prose =
    anchorMaps(repair, Keys.PROSE_AUDITS, workflowId, "prose audits are invalid").firstOrNull {
      digestAnchor(it[Keys.TREE_DIGEST], it[Keys.CHECKPOINT_ID])
    }
  if (prose != null) {
    return prose.getValue(Keys.TREE_DIGEST) as String to prose.getValue(Keys.CHECKPOINT_ID) as String
  }
  val outcome =
    anchorMaps(repair, Keys.OUTCOMES, workflowId, "outcomes are invalid").firstOrNull {
      digestAnchor(it[Keys.TREE_DIGEST], it[Keys.CHECKPOINT_ID])
    }
  if (outcome != null) {
    return outcome.getValue(Keys.TREE_DIGEST) as String to outcome.getValue(Keys.CHECKPOINT_ID) as String
  }
  throw invalidAcceptanceCriterionRepairStateSchema(
    workflowId,
    "legacy accepted repair has no closure tree and checkpoint to record final acceptance",
  )
}

private fun auditedRefs(
  criteria: List<Map<String, Any?>>,
  workflowId: String,
): List<String> =
  criteria.mapNotNull { criterion ->
    val ref =
      criterion[Keys.CANONICAL_REF] as? String
        ?: throw invalidAcceptanceCriterionRepairStateSchema(workflowId, "criterion reference is invalid")
    val disposition =
      criterion[Keys.SOURCE_DISPOSITION] as? String
        ?: throw invalidAcceptanceCriterionRepairStateSchema(workflowId, "criterion disposition is invalid")
    if (disposition == AcceptanceCriterionSourceDisposition.NOT_APPLICABLE.wireValue) null else ref
  }.sorted()

private fun stringValue(
  repair: Map<String, Any?>,
  key: String,
  workflowId: String,
): String = repair[key] as? String ?: throw invalidAcceptanceCriterionRepairStateSchema(workflowId, "$key is invalid")

private fun acceptanceDigest(parts: List<String>): String {
  val material = parts.joinToString("\n")
  return MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
}

private fun refuse(
  workflowId: String,
  reason: String,
): Nothing = throw invalidAcceptanceCriterionRepairStateSchema(workflowId, reason)

private fun digestAnchor(
  treeDigest: Any?,
  checkpointId: Any?,
): Boolean =
  treeDigest is String && treeDigest.isSha256() &&
    checkpointId is String && checkpointId.isNotBlank() && checkpointId.length <= MAX_CHECKPOINT_LENGTH

private fun String.isSha256(): Boolean =
  length == DIGEST_HEX_LENGTH &&
    all {
        character ->
      character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
    }

private fun integral(value: Any?): Long? =
  when (value) {
    is Byte, is Short, is Int, is Long -> (value as Number).toLong()
    else -> null
  }

private fun anchorMaps(
  repair: Map<String, Any?>,
  key: String,
  workflowId: String,
  reason: String,
): List<Map<String, Any?>> {
  val value = repair[key] ?: return emptyList()
  return objectMaps(value, workflowId, reason)
}

private fun objectMaps(
  value: Any?,
  workflowId: String,
  reason: String,
): List<Map<String, Any?>> {
  val items = value as? List<*> ?: throw invalidAcceptanceCriterionRepairStateSchema(workflowId, reason)
  return items.map { item -> stringKeyedMap(item, workflowId, reason) }
}

private fun stringKeyedMap(
  value: Any?,
  workflowId: String,
  reason: String,
): MutableMap<String, Any?> {
  val entries = value as? Map<*, *> ?: throw invalidAcceptanceCriterionRepairStateSchema(workflowId, reason)
  val copy = LinkedHashMap<String, Any?>(entries.size)
  entries.forEach { (key, entry) ->
    val name = key as? String ?: throw invalidAcceptanceCriterionRepairStateSchema(workflowId, reason)
    copy[name] = entry
  }
  return copy
}
