package skillbill.engine.goalrunner.archive

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.goal.GoalArchiveEnvelopeKeys
import skillbill.contracts.workflow.goal.GoalArchiveRecordKeys
import skillbill.contracts.workflow.goal.GoalPlanningPreparationPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.engine.goalplanning.toGoalPlanningPreparationRecord
import skillbill.error.core.catchingNonCancellation
import skillbill.error.goal.invalidGoalArchiveSchemaError
import skillbill.error.goal.unreadableGoalArchiveZipError
import skillbill.ports.goalrunner.model.GoalArchiveEnvelope
import skillbill.ports.goalrunner.model.GoalArchiveOwnedSnapshot
import skillbill.ports.goalrunner.model.GoalArchiveOwnedTables
import skillbill.ports.goalrunner.model.GoalArchiveTableRow
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.workflow.WorkflowArtifactWireEncoder
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.artifact.decodePhaseOutputRepairEvidenceFromArtifact
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal data class ParsedGoalArchiveBundle(
  val fingerprint: String,
  val envelope: GoalArchiveEnvelope,
  val specEntries: List<Pair<String, ByteArray>>,
  val snapshot: GoalArchiveOwnedSnapshot,
)

internal object GoalArchiveBundleReader {
  fun read(
    archivePath: Path,
    workflowArtifactWireEncoder: WorkflowArtifactWireEncoder,
  ): ParsedGoalArchiveBundle {
    val absolute = archivePath.toAbsolutePath().normalize()
    val entries = readZipEntries(absolute)
    val envelopeBytes =
      entries[GoalArchiveEnvelopeKeys.ARCHIVE_JSON]
        ?: throw invalidGoalArchiveSchemaError(GoalArchiveEnvelopeKeys.ARCHIVE_JSON, "archive.json", "entry is missing")
    val envelope = parseGoalArchiveEnvelope(envelopeBytes)
    val workflows =
      readArchiveJsonList(entries, GoalArchiveEnvelopeKeys.WORKFLOWS_JSON).map {
        it.toWorkflowStateRecord(workflowArtifactWireEncoder)
      }
    val planningJson = requiredJsonObject(entries, GoalArchiveEnvelopeKeys.PLANNING_JSON, "records/planning.json")
    val ownedTablesJson =
      requiredJsonObject(entries, GoalArchiveEnvelopeKeys.OWNED_TABLES_JSON, "records/owned-tables.json")
    val specEntries =
      entries.filterKeys { name -> name.startsWith(GoalArchiveEnvelopeKeys.SPECS_PREFIX) }.map { (name, bytes) ->
        name.removePrefix(GoalArchiveEnvelopeKeys.SPECS_PREFIX) to bytes
      }
    return ParsedGoalArchiveBundle(
      fingerprint = archiveFingerprint(entries),
      envelope = envelope,
      specEntries = specEntries,
      snapshot =
        GoalArchiveOwnedSnapshot(
          workflows = workflows,
          sharedPreplans =
            decodeArchiveMapList(planningJson, GoalArchiveRecordKeys.SHARED_PREPLANS) {
              it.toSharedGoalPreplanCheckpoint()
            },
          subtaskPlans =
            decodeArchiveMapList(planningJson, GoalArchiveRecordKeys.SUBTASK_PLANS) {
              it.toGoalSubtaskPlanCheckpoint()
            },
          legacyPreparations =
            decodeArchiveMapList(planningJson, GoalArchiveRecordKeys.LEGACY_PREPARATIONS) {
              it.toGoalPlanningPreparationRecord()
            },
          ownedTables = decodeOwnedTables(ownedTablesJson),
        ),
    )
  }
}

private fun archiveFingerprint(entries: Map<String, ByteArray>): String {
  val digest = MessageDigest.getInstance("SHA-256")
  entries.toSortedMap().forEach { (name, bytes) ->
    val nameBytes = name.toByteArray(Charsets.UTF_8)
    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(nameBytes.size).array())
    digest.update(nameBytes)
    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    digest.update(bytes)
  }
  return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun readZipEntries(absolute: Path): Map<String, ByteArray> {
  if (!absolute.toString().endsWith(".zip")) {
    throw unreadableGoalArchiveZipError(absolute.toString(), "archive path must end with .zip")
  }
  return catchingNonCancellation {
    val entries = linkedMapOf<String, ByteArray>()
    ZipInputStream(Files.newInputStream(absolute)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        if (!entry.isDirectory) {
          entries[entry.name.replace('\\', '/')] = zip.readBytes()
        }
        zip.closeEntry()
      }
    }
    entries
  }.getOrElse { error ->
    throw unreadableGoalArchiveZipError(
      absolute.toString(),
      error.message ?: error::class.simpleName.orEmpty(),
      error,
    )
  }
}

private fun requiredJsonObject(
  entries: Map<String, ByteArray>,
  entryName: String,
  label: String,
): Map<String, Any?> {
  val bytes =
    entries[entryName]
      ?: throw invalidGoalArchiveSchemaError(entryName, label, "entry is missing")
  return JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(bytes.decodeToString()))
    ?: throw invalidGoalArchiveSchemaError(entryName, label, "payload must be a JSON object")
}

private fun parseGoalArchiveEnvelope(bytes: ByteArray): GoalArchiveEnvelope {
  val map =
    JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(bytes.decodeToString()))
      ?: throw invalidGoalArchiveSchemaError(
        GoalArchiveEnvelopeKeys.ARCHIVE_JSON,
        "archive.json",
        "must be a JSON object",
      )
  val contractVersion = map.stringField(SharedPayloadKeys.CONTRACT_VERSION)
  val issueKey = map.stringField(SharedPayloadKeys.ISSUE_KEY)
  val featureBranch = map.stringField(GoalArchiveEnvelopeKeys.FEATURE_BRANCH)
  val commitSha = map.stringField(GoalArchiveEnvelopeKeys.COMMIT_SHA)
  val exportedAt = map.stringField(GoalArchiveEnvelopeKeys.EXPORTED_AT)
  return GoalArchiveEnvelope(contractVersion, issueKey, featureBranch, commitSha, exportedAt)
}

private fun readArchiveJsonList(
  entries: Map<String, ByteArray>,
  entryName: String,
): List<Map<String, Any?>> {
  val bytes =
    entries[entryName]
      ?: throw invalidGoalArchiveSchemaError(entryName, entryName, "entry is missing")
  return JsonCodec.anyToStringAnyMapList(JsonCodec.parseValue(bytes.decodeToString()))
    ?: throw invalidGoalArchiveSchemaError(entryName, entryName, "payload must be a JSON array")
}

private fun <T> decodeArchiveMapList(
  source: Map<String, Any?>,
  key: String,
  decode: (Map<String, Any?>) -> T,
): List<T> {
  if (key !in source) return emptyList()
  val raw =
    source[key] as? List<*>
      ?: throw invalidGoalArchiveSchemaError(GoalArchiveEnvelopeKeys.PLANNING_JSON, key, "must be a JSON array")
  return raw.map { item ->
    val map =
      (item as? Map<*, *>)?.let { JsonCodec.anyToStringAnyMap(it) }
        ?: throw invalidGoalArchiveSchemaError(
          GoalArchiveEnvelopeKeys.PLANNING_JSON,
          key,
          "each item must be a JSON object",
        )
    decode(map)
  }
}

private fun decodeOwnedTables(json: Map<String, Any?>): GoalArchiveOwnedTables {
  val tables = linkedMapOf<String, List<GoalArchiveTableRow>>()
  json.forEach { (table, value) ->
    val rows =
      value as? List<*>
        ?: throw invalidGoalArchiveSchemaError(
          GoalArchiveEnvelopeKeys.OWNED_TABLES_JSON,
          table,
          "must be a JSON array",
        )
    tables[table] =
      rows.map { row ->
        val map =
          (row as? Map<*, *>)?.let { JsonCodec.anyToStringAnyMap(it) }
            ?: throw invalidGoalArchiveSchemaError(
              GoalArchiveEnvelopeKeys.OWNED_TABLES_JSON,
              table,
              "each row must be a JSON object",
            )
        GoalArchiveTableRow(map)
      }
  }
  return GoalArchiveOwnedTables.of(tables)
}

private fun Map<String, Any?>.toWorkflowStateRecord(encoder: WorkflowArtifactWireEncoder): WorkflowStateRecord {
  val typedRaw = this[GoalArchiveRecordKeys.TYPED_ARTIFACTS]
  val typedWire = if (typedRaw == null) "{}" else JsonCodec.valueToJsonString(typedRaw)
  val modeWire = this[WorkflowWirePayloadKeys.MODE]?.toString()
  return WorkflowStateRecord(
    workflowId = stringField(SharedPayloadKeys.WORKFLOW_ID),
    sessionId = stringField(WorkflowWirePayloadKeys.SESSION_ID),
    workflowName = stringField(WorkflowWirePayloadKeys.WORKFLOW_NAME),
    contractVersion = stringField(SharedPayloadKeys.CONTRACT_VERSION),
    workflowStatus = stringField(WorkflowWirePayloadKeys.WORKFLOW_STATUS),
    currentStepId = stringField(WorkflowWirePayloadKeys.CURRENT_STEP_ID),
    stepsJson = stringField(GoalArchiveRecordKeys.STEPS_JSON),
    artifactsJson = stringField(GoalArchiveRecordKeys.ARTIFACTS_JSON),
    startedAt = this[WorkflowWirePayloadKeys.STARTED_AT]?.toString(),
    updatedAt = this[WorkflowWirePayloadKeys.UPDATED_AT]?.toString(),
    finishedAt = this[WorkflowWirePayloadKeys.FINISHED_AT]?.toString(),
    mode =
      modeWire?.let { wire ->
        FeatureTaskWorkflowMode.fromWireValue(wire)
          ?: throw invalidGoalArchiveSchemaError(
            GoalArchiveEnvelopeKeys.WORKFLOWS_JSON,
            WorkflowWirePayloadKeys.MODE,
            "unsupported value '$wire'",
          )
      },
    implementationSkill = this[GoalArchiveRecordKeys.IMPLEMENTATION_SKILL]?.toString(),
    issueKey = this[SharedPayloadKeys.ISSUE_KEY]?.toString(),
    stateEnteredAt = this[GoalArchiveRecordKeys.STATE_ENTERED_AT]?.toString(),
    stateEnteredAtEstimated = this[GoalArchiveRecordKeys.STATE_ENTERED_AT_ESTIMATED] as? Boolean ?: false,
    typedArtifacts = encoder.decode(typedWire),
  )
}

private fun Map<String, Any?>.toSharedGoalPreplanCheckpoint(): SharedGoalPreplanCheckpoint {
  val identity = identityField()
  return SharedGoalPreplanCheckpoint(
    identity = identity,
    preparationStatus = preparationStatusField(),
    provenance = provenanceField(),
    payloadSha256 = stringField(GoalPlanningPreparationPayloadKeys.PAYLOAD_SHA256),
    preplanPayload = stringField(GoalPlanningPreparationPayloadKeys.PREPLAN_PAYLOAD),
    repairEvidence = repairEvidenceField(GoalPlanningPreparationPayloadKeys.REPAIR_EVIDENCE),
    createdAt = this[GoalPlanningPreparationPayloadKeys.CREATED_AT]?.toString().orEmpty(),
    contractVersion = stringField(SharedPayloadKeys.CONTRACT_VERSION),
  )
}

private fun Map<String, Any?>.toGoalSubtaskPlanCheckpoint(): GoalSubtaskPlanCheckpoint {
  val identity = identityField()
  return GoalSubtaskPlanCheckpoint(
    identity = identity,
    subtaskId = numberField(SharedPayloadKeys.SUBTASK_ID),
    manifestOrder = numberField(GoalPlanningPreparationPayloadKeys.MANIFEST_ORDER),
    governedSubSpecPath = stringField(GoalPlanningPreparationPayloadKeys.GOVERNED_SUB_SPEC_PATH),
    subSpecHash = stringField(GoalPlanningPreparationPayloadKeys.SUB_SPEC_HASH),
    preparationStatus = preparationStatusField(),
    provenance = provenanceField(),
    payloadSha256 = stringField(GoalPlanningPreparationPayloadKeys.PAYLOAD_SHA256),
    planPayload = stringField(GoalPlanningPreparationPayloadKeys.PLAN_PAYLOAD),
    repairEvidence = repairEvidenceField(GoalPlanningPreparationPayloadKeys.REPAIR_EVIDENCE),
    createdAt = this[GoalPlanningPreparationPayloadKeys.CREATED_AT]?.toString().orEmpty(),
    contractVersion = stringField(SharedPayloadKeys.CONTRACT_VERSION),
  )
}

private fun Map<String, Any?>.identityField(): GoalPlanningIdentity {
  val identityMap = this[GoalPlanningPreparationPayloadKeys.IDENTITY] as? Map<*, *>
  val map = identityMap?.let { JsonCodec.anyToStringAnyMap(it) } ?: this
  return GoalPlanningIdentity(
    parentGoalWorkflowId = map.stringField(GoalPlanningPreparationPayloadKeys.PARENT_GOAL_WORKFLOW_ID),
    normalizedIssueKey = map.stringField(GoalPlanningPreparationPayloadKeys.NORMALIZED_ISSUE_KEY),
    repositoryIdentity = map.stringField(GoalPlanningPreparationPayloadKeys.REPOSITORY_IDENTITY),
  )
}

private fun Map<String, Any?>.provenanceField(): GoalPlanningContractProvenance {
  val provenanceMap =
    (this[GoalPlanningPreparationPayloadKeys.PROVENANCE] as? Map<*, *>)?.let { JsonCodec.anyToStringAnyMap(it) }
      ?: emptyMap()
  return GoalPlanningContractProvenance(
    parentSpecHash = provenanceMap.stringField(GoalPlanningPreparationPayloadKeys.PARENT_SPEC_HASH),
    decompositionManifestHash =
      provenanceMap.stringField(GoalPlanningPreparationPayloadKeys.DECOMPOSITION_MANIFEST_HASH),
    planningContractId = provenanceMap.stringField(GoalPlanningPreparationPayloadKeys.PLANNING_CONTRACT_ID),
    planningContractVersion = provenanceMap.stringField(GoalPlanningPreparationPayloadKeys.PLANNING_CONTRACT_VERSION),
    phaseOutputContractId = provenanceMap.stringField(GoalPlanningPreparationPayloadKeys.PHASE_OUTPUT_CONTRACT_ID),
    phaseOutputContractVersion =
      provenanceMap.stringField(GoalPlanningPreparationPayloadKeys.PHASE_OUTPUT_CONTRACT_VERSION),
  )
}

private fun Map<String, Any?>.preparationStatusField(): GoalPlanningPreparationState {
  val wire = stringField(GoalPlanningPreparationPayloadKeys.PREPARATION_STATUS)
  return GoalPlanningPreparationState.fromWireValue(wire)
    ?: throw invalidGoalArchiveSchemaError("planning", "preparation_status", "unsupported value '$wire'")
}

private fun Map<String, Any?>.repairEvidenceField(key: String): FeatureTaskRuntimePhaseOutputRepairEvidence? {
  val raw = this[key] ?: return null
  val map = (raw as? Map<*, *>)?.let { JsonCodec.anyToStringAnyMap(it) } ?: return null
  return decodePhaseOutputRepairEvidenceFromArtifact(map)
}

private fun Map<String, Any?>.stringField(key: String): String = this[key]?.toString().orEmpty()

private fun Map<String, Any?>.numberField(key: String): Int {
  val raw =
    this[key]
      ?: throw invalidGoalArchiveSchemaError(GoalArchiveEnvelopeKeys.PLANNING_JSON, key, "is required")
  return (raw as? Number)?.toInt()
    ?: throw invalidGoalArchiveSchemaError(GoalArchiveEnvelopeKeys.PLANNING_JSON, key, "must be a number")
}
