package skillbill.infrastructure.sqlite.blockincident

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.blockincident.BLOCK_INCIDENT_CONTRACT_VERSION
import skillbill.error.blockincident.BlockIncidentFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCorrelatedField
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCorrelation
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceProvenance
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceReference
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceReferenceAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFailure
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFieldAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentIdentityAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentReasonRetention
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentScope
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentState
import java.time.Instant
import java.time.format.DateTimeParseException
import skillbill.contracts.workflow.identity.blockincident.BlockIncidentEvidenceReferenceKeys as EvidenceKeys
import skillbill.contracts.workflow.identity.blockincident.BlockIncidentOccurrenceKeys as Keys

internal object SqliteBlockIncidentOccurrenceCodec {
  private const val DOCUMENT_OCCURRENCE = "occurrence"
  private const val DOCUMENT_ALIASES = "aliases"
  private const val DOCUMENT_STATE = "state"
  private const val ALIAS_KIND = "kind"
  private const val ALIAS_VALUE = "value"
  private const val STATE_DIAGNOSIS = "diagnosis"
  private const val STATE_RECOVERY = "recovery"
  private const val STATE_REPORT = "report"
  private const val STATE_REVISION = "revision"
  private const val STATE_DEADLINE = "deadline"
  private const val STATE_RETRY_COUNT = "retry_count"
  private const val STATE_NEXT_ELIGIBLE_AT = "next_eligible_at"
  private const val STATE_CLAIM = "claim"
  private const val CLAIM_OWNER_TOKEN = "owner_token"
  private const val CLAIM_OWNER_GENERATION = "owner_generation"
  private const val CLAIM_HOST_IDENTITY = "host_identity"
  private const val CLAIM_PROCESS_ID = "process_id"
  private const val CLAIM_EXPIRES_AT = "expires_at"
  private val LEGACY_OCCURRENCE_KEYS =
    setOf(
      Keys.OCCURRENCE_TOKEN, Keys.WORKFLOW_ID, Keys.PARENT_WORKFLOW_ID, Keys.PHASE_ID, Keys.PHASE_RUN_ID,
      Keys.INVOCATION_ID, Keys.EXECUTION_ID, Keys.ATTEMPT, Keys.GENERATION, Keys.ORIGIN, Keys.REASON,
      Keys.REASON_TRUNCATED, Keys.ORIGINAL_REASON_LENGTH, Keys.ORIGINAL_REASON_DIGEST, Keys.FAILURE_CODE,
      Keys.FAILURE_DISPOSITION, Keys.THROWABLE_TYPE, Keys.EXIT_CODE, Keys.OBSERVED_AT, Keys.EVIDENCE_AVAILABILITY,
      Keys.IDENTITY_AVAILABILITY, Keys.REPAIR_TURN, Keys.OUTPUT_GENERATION, Keys.MONITOR_SELECTOR,
      Keys.PULL_REQUEST_ID, Keys.PULL_REQUEST_URL, Keys.HEAD_REPOSITORY, Keys.HEAD_BRANCH, Keys.WORKTREE_PATH,
      Keys.EXIT_BEHAVIOR, Keys.EVIDENCE_REFERENCES,
    )
  private val OCCURRENCE_KEYS = LEGACY_OCCURRENCE_KEYS + setOf(Keys.FIELD_AVAILABILITY, Keys.REASON_RETENTION)
  private val CORRELATED_FIELD_KEYS = BlockIncidentCorrelatedField.entries.map { it.wireValue }.toSet()

  val HISTORICAL_FIELD_AVAILABILITY: Map<BlockIncidentCorrelatedField, BlockIncidentFieldAvailability> =
    BlockIncidentCorrelatedField.entries.associateWith { BlockIncidentFieldAvailability.UNKNOWN_HISTORICAL }
  private val EVIDENCE_KEYS =
    setOf(
      EvidenceKeys.SOURCE, EvidenceKeys.REFERENCE, EvidenceKeys.DIGEST, EvidenceKeys.AVAILABILITY,
      EvidenceKeys.REPAIR_TURN, EvidenceKeys.OUTPUT_GENERATION, EvidenceKeys.KIND, EvidenceKeys.PROVENANCE,
      EvidenceKeys.BYTE_SIZE, EvidenceKeys.SOURCE_IDENTITY, EvidenceKeys.RETAINED_REFERENCE_ID,
      EvidenceKeys.SOURCE_OFFSET, EvidenceKeys.SOURCE_LINE, EvidenceKeys.SOURCE_COLUMN,
    )

  fun encode(occurrence: BlockIncidentOccurrence): String = JsonCodec.valueToJsonString(occurrenceFields(occurrence))

  fun encodeIncident(incident: BlockIncident): String =
    JsonCodec.valueToJsonString(
      linkedMapOf(
        SharedPayloadKeys.CONTRACT_VERSION to BLOCK_INCIDENT_CONTRACT_VERSION,
        DOCUMENT_OCCURRENCE to occurrenceFields(incident.occurrence),
        DOCUMENT_ALIASES to
          incident.aliases.map { alias ->
            linkedMapOf(ALIAS_KIND to alias.kind.wireValue, ALIAS_VALUE to alias.value)
          },
        DOCUMENT_STATE to stateFields(incident.state),
      ),
    )

  private fun stateFields(state: BlockIncidentState): Map<String, Any?> =
    linkedMapOf(
      STATE_DIAGNOSIS to state.diagnosis.wireValue,
      STATE_RECOVERY to state.recovery.wireValue,
      STATE_REPORT to state.report.wireValue,
      STATE_REVISION to state.revision,
      STATE_DEADLINE to state.deadline.toString(),
      STATE_RETRY_COUNT to state.retryCount,
      STATE_NEXT_ELIGIBLE_AT to state.nextEligibleAt?.toString(),
      STATE_CLAIM to
        state.claim?.let { claim ->
          linkedMapOf(
            CLAIM_OWNER_TOKEN to claim.ownerToken,
            CLAIM_OWNER_GENERATION to claim.ownerGeneration,
            CLAIM_HOST_IDENTITY to claim.hostIdentity,
            CLAIM_PROCESS_ID to claim.processId,
            CLAIM_EXPIRES_AT to claim.expiresAt.toString(),
          )
        },
    )

  private fun occurrenceFields(occurrence: BlockIncidentOccurrence): Map<String, Any?> =
    linkedMapOf(
      Keys.OCCURRENCE_TOKEN to occurrence.occurrenceToken,
      Keys.WORKFLOW_ID to occurrence.workflowId,
      Keys.PARENT_WORKFLOW_ID to occurrence.parentWorkflowId,
      Keys.PHASE_ID to occurrence.phaseId,
      Keys.PHASE_RUN_ID to occurrence.phaseRunId,
      Keys.INVOCATION_ID to occurrence.invocationId,
      Keys.EXECUTION_ID to occurrence.executionId,
      Keys.ATTEMPT to occurrence.attempt,
      Keys.GENERATION to occurrence.generation,
      Keys.ORIGIN to occurrence.origin.wireValue,
      Keys.REASON to occurrence.reason,
      Keys.REASON_TRUNCATED to occurrence.reasonTruncated,
      Keys.ORIGINAL_REASON_LENGTH to occurrence.originalReasonLength,
      Keys.ORIGINAL_REASON_DIGEST to occurrence.originalReasonDigest,
      Keys.FAILURE_CODE to occurrence.failureCode,
      Keys.FAILURE_DISPOSITION to occurrence.failureDisposition,
      Keys.THROWABLE_TYPE to occurrence.throwableType,
      Keys.EXIT_CODE to occurrence.exitCode,
      Keys.OBSERVED_AT to occurrence.observedAt.toString(),
      Keys.EVIDENCE_AVAILABILITY to occurrence.evidenceAvailability.wireValue,
      Keys.IDENTITY_AVAILABILITY to occurrence.identityAvailability.wireValue,
      Keys.REPAIR_TURN to occurrence.repairTurn,
      Keys.OUTPUT_GENERATION to occurrence.outputGeneration,
      Keys.MONITOR_SELECTOR to occurrence.monitorSelector,
      Keys.PULL_REQUEST_ID to occurrence.pullRequestId,
      Keys.PULL_REQUEST_URL to occurrence.pullRequestUrl,
      Keys.HEAD_REPOSITORY to occurrence.headRepository,
      Keys.HEAD_BRANCH to occurrence.headBranch,
      Keys.WORKTREE_PATH to occurrence.worktreePath,
      Keys.EXIT_BEHAVIOR to occurrence.exitBehavior.wireValue,
      Keys.EVIDENCE_REFERENCES to occurrence.evidenceReferences.map(::encodeEvidence),
      Keys.FIELD_AVAILABILITY to
        BlockIncidentCorrelatedField.entries.associate { field ->
          field.wireValue to occurrence.fieldAvailability[field]?.wireValue
        },
      Keys.REASON_RETENTION to occurrence.reasonRetention.wireValue,
    )

  private fun encodeEvidence(reference: BlockIncidentEvidenceReference): Map<String, Any?> =
    linkedMapOf(
      EvidenceKeys.SOURCE to reference.source,
      EvidenceKeys.REFERENCE to reference.reference,
      EvidenceKeys.DIGEST to reference.digest,
      EvidenceKeys.AVAILABILITY to reference.availability.wireValue,
      EvidenceKeys.REPAIR_TURN to reference.repairTurn,
      EvidenceKeys.OUTPUT_GENERATION to reference.outputGeneration,
      EvidenceKeys.KIND to reference.kind.wireValue,
      EvidenceKeys.PROVENANCE to reference.provenance.wireValue,
      EvidenceKeys.BYTE_SIZE to reference.byteSize,
      EvidenceKeys.SOURCE_IDENTITY to reference.sourceIdentity,
      EvidenceKeys.RETAINED_REFERENCE_ID to reference.retainedReferenceId,
      EvidenceKeys.SOURCE_OFFSET to reference.sourceOffset,
      EvidenceKeys.SOURCE_LINE to reference.sourceLine,
      EvidenceKeys.SOURCE_COLUMN to reference.sourceColumn,
    )

  fun decode(raw: String): BlockIncidentOccurrence {
    val parsed =
      JsonCodec.parseObjectOrNull(raw)
        ?: throw corrupt("Stored block incident occurrence is not an object.")
    val legacy = parsed.keys == LEGACY_OCCURRENCE_KEYS
    val fields = if (legacy) parsed else parsed.exactly(OCCURRENCE_KEYS, "occurrence")
    return BlockIncidentOccurrence(
      occurrenceToken = fields.text(Keys.OCCURRENCE_TOKEN),
      scope =
        BlockIncidentScope(
          workflowId = fields.optionalText(Keys.WORKFLOW_ID),
          parentWorkflowId = fields.optionalText(Keys.PARENT_WORKFLOW_ID),
          phaseId = fields.optionalText(Keys.PHASE_ID),
          phaseRunId = fields.optionalText(Keys.PHASE_RUN_ID),
          invocationId = fields.optionalText(Keys.INVOCATION_ID),
          executionId = fields.optionalText(Keys.EXECUTION_ID),
          attempt = fields.optionalInt(Keys.ATTEMPT),
          generation = fields.optionalLong(Keys.GENERATION),
        ),
      failure =
        BlockIncidentFailure(
          origin = fields.enumValue(Keys.ORIGIN, BlockIncidentOrigin.entries) { it.wireValue },
          reason = fields.text(Keys.REASON),
          reasonTruncated = fields.boolean(Keys.REASON_TRUNCATED),
          originalReasonLength = fields.int(Keys.ORIGINAL_REASON_LENGTH),
          originalReasonDigest = fields.optionalText(Keys.ORIGINAL_REASON_DIGEST),
          reasonRetention =
            if (legacy) {
              BlockIncidentReasonRetention.UNKNOWN_HISTORICAL
            } else {
              fields.enumValue(Keys.REASON_RETENTION, BlockIncidentReasonRetention.entries) { it.wireValue }
            },
          failureCode = fields.optionalText(Keys.FAILURE_CODE),
          failureDisposition = fields.optionalText(Keys.FAILURE_DISPOSITION),
          throwableType = fields.optionalText(Keys.THROWABLE_TYPE),
          exitCode = fields.optionalInt(Keys.EXIT_CODE),
          exitBehavior = fields.enumValue(Keys.EXIT_BEHAVIOR, BlockIncidentExitBehavior.entries) { it.wireValue },
        ),
      observedAt = fields.instant(Keys.OBSERVED_AT),
      evidenceAvailability =
        fields.enumValue(Keys.EVIDENCE_AVAILABILITY, BlockIncidentEvidenceAvailability.entries) {
          it.wireValue
        },
      identityAvailability =
        fields.enumValue(Keys.IDENTITY_AVAILABILITY, BlockIncidentIdentityAvailability.entries) {
          it.wireValue
        },
      fieldAvailability = if (legacy) HISTORICAL_FIELD_AVAILABILITY else fields.fieldAvailability(),
      correlation =
        BlockIncidentCorrelation(
          repairTurn = fields.optionalInt(Keys.REPAIR_TURN),
          outputGeneration = fields.optionalLong(Keys.OUTPUT_GENERATION),
          monitorSelector = fields.optionalText(Keys.MONITOR_SELECTOR),
          pullRequestId = fields.optionalText(Keys.PULL_REQUEST_ID),
          pullRequestUrl = fields.optionalText(Keys.PULL_REQUEST_URL),
          headRepository = fields.optionalText(Keys.HEAD_REPOSITORY),
          headBranch = fields.optionalText(Keys.HEAD_BRANCH),
          worktreePath = fields.optionalText(Keys.WORKTREE_PATH),
        ),
      retainedEvidenceReferences =
        (fields[Keys.EVIDENCE_REFERENCES] as? JsonArray ?: throw corrupt("Stored evidence references are absent."))
          .map(::decodeEvidence),
    )
  }

  private fun JsonObject.fieldAvailability(): Map<BlockIncidentCorrelatedField, BlockIncidentFieldAvailability> {
    val entries =
      (get(Keys.FIELD_AVAILABILITY) as? JsonObject)?.exactly(CORRELATED_FIELD_KEYS, "field availability")
        ?: throw corrupt("Stored block incident field availability is not an object.")
    return BlockIncidentCorrelatedField.entries.associateWith { field ->
      entries.enumValue(field.wireValue, BlockIncidentFieldAvailability.entries) { it.wireValue }
    }
  }

  private fun decodeEvidence(element: JsonElement): BlockIncidentEvidenceReference {
    val fields =
      (element as? JsonObject)?.exactly(EVIDENCE_KEYS, "evidence reference")
        ?: throw corrupt("Stored evidence reference is not an object.")
    return BlockIncidentEvidenceReference(
      source = fields.text(EvidenceKeys.SOURCE),
      reference = fields.optionalText(EvidenceKeys.REFERENCE),
      digest = fields.optionalText(EvidenceKeys.DIGEST),
      availability =
        fields.enumValue(EvidenceKeys.AVAILABILITY, BlockIncidentEvidenceReferenceAvailability.entries) {
          it.wireValue
        },
      repairTurn = fields.optionalInt(EvidenceKeys.REPAIR_TURN),
      outputGeneration = fields.optionalLong(EvidenceKeys.OUTPUT_GENERATION),
      kind = fields.enumValue(EvidenceKeys.KIND, BlockIncidentEvidenceKind.entries) { it.wireValue },
      provenance =
        fields.enumValue(EvidenceKeys.PROVENANCE, BlockIncidentEvidenceProvenance.entries) { it.wireValue },
      byteSize = fields.optionalLong(EvidenceKeys.BYTE_SIZE),
      sourceIdentity = fields.optionalText(EvidenceKeys.SOURCE_IDENTITY),
      retainedReferenceId = fields.optionalText(EvidenceKeys.RETAINED_REFERENCE_ID),
      sourceOffset = fields.optionalInt(EvidenceKeys.SOURCE_OFFSET),
      sourceLine = fields.optionalInt(EvidenceKeys.SOURCE_LINE),
      sourceColumn = fields.optionalInt(EvidenceKeys.SOURCE_COLUMN),
    )
  }

  private fun JsonObject.exactly(
    keys: Set<String>,
    label: String,
  ): JsonObject =
    if (this.keys == keys) this else throw corrupt("Stored block incident $label has an unexpected key set.")

  private fun JsonObject.primitive(key: String): JsonPrimitive? =
    when (val value = getValue(key)) {
      JsonNull -> null
      is JsonPrimitive -> value
      else -> throw corrupt("Stored block incident $key is not a scalar.")
    }

  private fun JsonObject.optionalText(key: String): String? =
    primitive(key)?.let { value ->
      if (value.isString) value.content else throw corrupt("Stored block incident $key is not text.")
    }

  private fun JsonObject.text(key: String): String =
    optionalText(key) ?: throw corrupt("Stored block incident $key is absent.")

  private fun JsonObject.optionalLong(key: String): Long? =
    primitive(key)?.let { value ->
      value.takeUnless { it.isString }?.longOrNull ?: throw corrupt("Stored block incident $key is not an integer.")
    }

  private fun JsonObject.optionalInt(key: String): Int? =
    optionalLong(key)?.let { value ->
      if (value !in Int.MIN_VALUE..Int.MAX_VALUE) throw corrupt("Stored block incident $key overflows.")
      value.toInt()
    }

  private fun JsonObject.int(key: String): Int =
    optionalInt(key) ?: throw corrupt("Stored block incident $key is absent.")

  private fun JsonObject.boolean(key: String): Boolean =
    primitive(key)?.takeUnless { it.isString }?.booleanOrNull
      ?: throw corrupt("Stored block incident $key is not a boolean.")

  private fun JsonObject.instant(key: String): Instant =
    try {
      Instant.parse(text(key))
    } catch (failure: DateTimeParseException) {
      throw corrupt("Stored block incident $key is not an instant.", failure)
    }

  private fun <T> JsonObject.enumValue(
    key: String,
    values: List<T>,
    wireValue: (T) -> String,
  ): T {
    val raw = text(key)
    return values.firstOrNull { wireValue(it) == raw } ?: throw corrupt("Stored block incident $key is unsupported.")
  }

  private fun corrupt(
    message: String,
    cause: Throwable? = null,
  ) = SkillBillRuntimeException(BlockIncidentFailureCode.INVALID_SCHEMA, message, cause)
}
