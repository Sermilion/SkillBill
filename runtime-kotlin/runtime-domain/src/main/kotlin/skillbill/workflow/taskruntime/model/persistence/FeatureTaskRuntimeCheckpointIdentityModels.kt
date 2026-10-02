package skillbill.workflow.taskruntime.model.persistence

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.scaffold.wire.optionalString
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeCheckpointIdentityVersionError
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.text.sha256HexUtf8
import skillbill.workflow.model.persistence.artifact.appendBoundedHistoryBySequence
import skillbill.workflow.model.persistence.artifact.durableArtifactMapReader

internal const val FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_LIMIT: Int = 200

private const val OWNED_PATH_DIGEST_DELIMITER: Char = '\u0000'

const val FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID: String = "standalone"

const val FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE: String = "refs/skill-bill/checkpoints"

private const val CHECKPOINT_REF_PREFIX: String = FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE

fun featureTaskRuntimeCheckpointRefName(
  issueKey: String,
  subtaskId: String,
  sequenceNumber: Int,
): String = "$CHECKPOINT_REF_PREFIX/$issueKey/$subtaskId/$sequenceNumber"

data class FeatureTaskRuntimeCheckpointIdentity(
  val sequenceNumber: Int,
  val issueKey: String,
  val subtaskId: String,
  val checkpointRef: String,
  val branch: String,
  val phaseId: String,
  val generation: Int,
  val ownedPathDigest: String,
  val ownedPathCount: Int,
  val commitSha: String,
  val recordedAt: String,
  val loopId: String? = null,
  val parentSha: String? = null,
) {
  init {
    require(sequenceNumber >= 0) {
      "FeatureTaskRuntimeCheckpointIdentity.sequenceNumber must be non-negative, was $sequenceNumber."
    }
    require(issueKey.isNotBlank()) { "FeatureTaskRuntimeCheckpointIdentity.issueKey must be non-blank." }
    require(subtaskId.matches(SUBTASK_ID_PATTERN)) {
      "FeatureTaskRuntimeCheckpointIdentity.subtaskId must be a positive integer or " +
        "'$FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID', was '$subtaskId'."
    }
    require(checkpointRef.matches(CHECKPOINT_REF_PATTERN) && checkpointRef.length <= CHECKPOINT_REF_MAX_LENGTH) {
      "FeatureTaskRuntimeCheckpointIdentity.checkpointRef must be a bounded skill-bill checkpoint ref."
    }
    require(checkpointRef == featureTaskRuntimeCheckpointRefName(issueKey, subtaskId, sequenceNumber)) {
      "FeatureTaskRuntimeCheckpointIdentity.checkpointRef '$checkpointRef' does not derive from issueKey " +
        "'$issueKey', subtaskId '$subtaskId' and sequenceNumber $sequenceNumber; the ref is the identity, so a " +
        "ref naming a different authority boundary than its own record is rejected."
    }
    require(branch.isNotBlank()) { "FeatureTaskRuntimeCheckpointIdentity.branch must be non-blank." }
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimeCheckpointIdentity.phaseId must be non-blank." }
    require(generation >= 0) {
      "FeatureTaskRuntimeCheckpointIdentity.generation must be non-negative, was $generation."
    }
    require(ownedPathCount >= 0) {
      "FeatureTaskRuntimeCheckpointIdentity.ownedPathCount must be non-negative, was $ownedPathCount."
    }
    require(ownedPathDigest.matches(DIGEST_PATTERN)) {
      "FeatureTaskRuntimeCheckpointIdentity.ownedPathDigest must be a lowercase SHA-256 hex digest."
    }
    require(commitSha.matches(SHA_PATTERN)) {
      "FeatureTaskRuntimeCheckpointIdentity.commitSha must be a lowercase commit sha."
    }
    require(recordedAt.isNotBlank()) { "FeatureTaskRuntimeCheckpointIdentity.recordedAt must be non-blank." }
    parentSha?.let { sha ->
      require(sha.matches(SHA_PATTERN)) {
        "FeatureTaskRuntimeCheckpointIdentity.parentSha must be a lowercase commit sha when present."
      }
    }
    loopId?.let { id -> require(id.isNotBlank()) { "FeatureTaskRuntimeCheckpointIdentity.loopId must be non-blank." } }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      "sequence_number" to sequenceNumber,
      SharedPayloadKeys.ISSUE_KEY to issueKey,
      SharedPayloadKeys.SUBTASK_ID to subtaskId,
      "checkpoint_ref" to checkpointRef,
      DecompositionPlanningPayloadKeys.BRANCH to branch,
      SharedPayloadKeys.PHASE_ID to phaseId,
      "generation" to generation,
      "owned_path_digest" to ownedPathDigest,
      "owned_path_count" to ownedPathCount,
      DecompositionManifestPayloadKeys.COMMIT_SHA to commitSha,
      "recorded_at" to recordedAt,
    ).apply {
      loopId?.let { put("loop_id", it) }
      parentSha?.let { put("parent_sha", it) }
    }

  companion object {
    private val DIGEST_PATTERN = Regex("^[0-9a-f]{64}$")
    private val SHA_PATTERN = Regex("^[0-9a-f]{40,64}$")
    private val SUBTASK_ID_PATTERN = Regex("^([0-9]+|$FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID)$")
    private val CHECKPOINT_REF_PATTERN =
      Regex("^$CHECKPOINT_REF_PREFIX/.+/($FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID|[0-9]+)/[0-9]+$")
    private const val CHECKPOINT_REF_MAX_LENGTH: Int = 255

    private val ALLOWED_FIELDS =
      setOf(
        "sequence_number",
        SharedPayloadKeys.ISSUE_KEY,
        SharedPayloadKeys.SUBTASK_ID,
        "checkpoint_ref",
        DecompositionPlanningPayloadKeys.BRANCH,
        SharedPayloadKeys.PHASE_ID,
        "generation",
        "owned_path_digest",
        "owned_path_count",
        DecompositionManifestPayloadKeys.COMMIT_SHA,
        "recorded_at",
        "loop_id",
        "parent_sha",
      )

    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeCheckpointIdentity {
      val unexpected = raw.keys - ALLOWED_FIELDS
      if (unexpected.isNotEmpty()) {
        checkpointIdentityError(
          "Feature-task-runtime checkpoint-identity entry carries unsupported fields " +
            "${unexpected.sorted()}; the store is quarantined and regenerated rather than reinterpreted.",
        )
      }
      return try {
        val reader = durableArtifactMapReader(raw)
        FeatureTaskRuntimeCheckpointIdentity(
          sequenceNumber = reader.requiredInt("sequence_number"),
          issueKey = reader.requiredString(SharedPayloadKeys.ISSUE_KEY),
          subtaskId = reader.requiredString(SharedPayloadKeys.SUBTASK_ID),
          checkpointRef = reader.requiredString("checkpoint_ref"),
          branch = reader.requiredString(DecompositionPlanningPayloadKeys.BRANCH),
          phaseId = reader.requiredString(SharedPayloadKeys.PHASE_ID),
          generation = reader.requiredInt("generation"),
          ownedPathDigest = reader.requiredString("owned_path_digest"),
          ownedPathCount = reader.requiredInt("owned_path_count"),
          commitSha = reader.requiredString(DecompositionManifestPayloadKeys.COMMIT_SHA),
          recordedAt = reader.requiredString("recorded_at"),
          loopId = reader.optionalString("loop_id"),
          parentSha = reader.optionalString("parent_sha"),
        )
      } catch (error: IllegalArgumentException) {
        checkpointIdentityError(
          "Feature-task-runtime checkpoint-identity entry is malformed: ${error.message}",
        )
      }
    }
  }
}

fun featureTaskRuntimeOwnedPathDigest(ownedPaths: List<String>): String {
  val normalized = ownedPaths.filter(String::isNotBlank).distinct().sorted()
  val framed =
    normalized.joinToString(OWNED_PATH_DIGEST_DELIMITER.toString()) { path ->
      "${path.length}:$path"
    }
  return sha256HexUtf8(framed)
}

internal fun featureTaskRuntimeCheckpointIdentitiesToArtifact(
  identities: List<FeatureTaskRuntimeCheckpointIdentity>,
): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION,
    "checkpoints" to identities.map { it.toArtifactMap() },
  )

internal fun featureTaskRuntimeCheckpointIdentitiesFromArtifact(raw: Any?): List<FeatureTaskRuntimeCheckpointIdentity> {
  if (raw == null) return emptyList()
  val map =
    JsonCodec.anyToStringAnyMap(raw)
      ?: checkpointIdentityError("Feature-task-runtime checkpoint-identity record must be an object.")
  val version = map[SharedPayloadKeys.CONTRACT_VERSION] as? String
  if (version != FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION) {
    throw InvalidFeatureTaskRuntimeCheckpointIdentityVersionError(
      expectedContractVersion = FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION,
      actualContractVersion = version.orEmpty(),
    )
  }
  val checkpoints =
    map["checkpoints"] as? List<*>
      ?: checkpointIdentityError(
        "Feature-task-runtime checkpoint-identity record must carry a 'checkpoints' array.",
      )
  val decoded =
    checkpoints.map { entry ->
      FeatureTaskRuntimeCheckpointIdentity.fromArtifactMap(
        JsonCodec.anyToStringAnyMap(entry)
          ?: checkpointIdentityError("Feature-task-runtime checkpoint-identity entry must be an object."),
      )
    }
  val duplicateRefs = decoded.groupBy { it.checkpointRef }.filterValues { it.size > 1 }.keys
  if (duplicateRefs.isNotEmpty()) {
    checkpointIdentityError(
      "Feature-task-runtime checkpoint-identity history records checkpoint ref(s) ${duplicateRefs.sorted()} " +
        "more than once; one checkpoint ref yields exactly one identity record.",
    )
  }
  return decoded
}

fun featureTaskRuntimeAppendCheckpointIdentity(
  existing: List<FeatureTaskRuntimeCheckpointIdentity>,
  entry: FeatureTaskRuntimeCheckpointIdentity,
  retentionLimit: Int = FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_LIMIT,
): List<FeatureTaskRuntimeCheckpointIdentity> {
  if (existing.any { it.checkpointRef == entry.checkpointRef }) return existing
  return appendBoundedHistoryBySequence(
    existing = existing.map { it.toArtifactMap() },
    entry = entry.toArtifactMap(),
    retentionLimit = retentionLimit,
  ).map { raw ->
    FeatureTaskRuntimeCheckpointIdentity.fromArtifactMap(
      JsonCodec.anyToStringAnyMap(raw)
        ?: checkpointIdentityError("Checkpoint identity history entry must decode to an object."),
    )
  }
}

private fun checkpointIdentityError(detail: String): Nothing = throw InvalidWorkflowStateSchemaError(detail)
