package skillbill.infrastructure.workflow.featuretask

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_SHARED_EVIDENCE_PROJECTION_CONTRACT_VERSION
import skillbill.error.featuretask.FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeSharedEvidenceProjectionSchemaValidator
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolution
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeSharedReviewEvidenceReference
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceArtifact
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceDiffPayloadRef
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceFileEntry
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceHunkEntry
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal fun readStored(
  mapper: ObjectMapper,
  artifactDir: Path,
  fingerprint: String,
  workflowId: String,
  storePath: String,
): FeatureTaskRuntimeSharedEvidenceResolution? {
  val envelopePath = artifactDir.resolve(SHARED_EVIDENCE_ENVELOPE_FILE)
  val envelopeLabel = envelopePath.toString()
  val envelope = readEnvelope(mapper, envelopePath) ?: return null
  val recorded = recordedFingerprint(envelope, envelopeLabel, fingerprint) ?: return null
  return intactPayloadRef(artifactDir, envelope, envelopeLabel)?.let { payloadRef ->
    readPayloadText(artifactDir.resolve(payloadRef.relativePath))?.let { payloadText ->
      resolutionOf(
        StoredEnvelopePayload(envelope, recorded, payloadRef, payloadText),
        StoredReadContext(envelopeLabel, workflowId, storePath),
      )
    }
  }
}

private fun recordedFingerprint(
  envelope: ObjectNode,
  envelopeLabel: String,
  addressed: String,
): String? {
  val recorded = envelope.path("fingerprint").asText("")
  if (recorded.isBlank()) {
    return degraded("stored_envelope_fingerprint", "re-derive", addressed, "blank at $envelopeLabel")
  }
  if (recorded != addressed) {
    throw FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError(
      addressedFingerprint = addressed,
      recordedFingerprint = recorded,
      sourceLabel = envelopeLabel,
    )
  }
  return recorded
}

private data class StoredEnvelopePayload(
  val envelope: ObjectNode,
  val recorded: String,
  val payloadRef: FeatureTaskRuntimeSharedEvidenceDiffPayloadRef,
  val payloadText: String,
)

private data class StoredReadContext(
  val envelopeLabel: String,
  val workflowId: String,
  val storePath: String,
)

private data class StoredEnvelopeIndex(
  val files: List<FeatureTaskRuntimeSharedEvidenceFileEntry>,
  val hunks: List<FeatureTaskRuntimeSharedEvidenceHunkEntry>,
)

private fun readStoredIndex(
  envelope: ObjectNode,
  envelopeLabel: String,
): StoredEnvelopeIndex? {
  val fileValues =
    envelope.path("files").map { it.path("path").asText("") to it.path("change_kind").asText("") }
  val hunkValues =
    envelope.path("hunks").map { it.path("path").asText("") to it.path("header").asText("") }
  val fileViolation =
    fileValues.firstNotNullOfOrNull { (path, changeKind) ->
      FeatureTaskRuntimeSharedEvidenceFileEntry.violation(path, changeKind)
    }
  val hunkViolation =
    hunkValues.firstNotNullOfOrNull { (path, header) ->
      FeatureTaskRuntimeSharedEvidenceHunkEntry.violation(path, header)
    }
  val indexViolation = fileViolation ?: hunkViolation
  if (indexViolation != null) {
    return degraded(
      seam = "stored_envelope_index",
      used = "re-derive",
      expected = "non-blank file and hunk entries at $envelopeLabel",
      cause = "IllegalArgumentException: $indexViolation",
    )
  }
  return StoredEnvelopeIndex(
    files = fileValues.map { (path, changeKind) -> FeatureTaskRuntimeSharedEvidenceFileEntry(path, changeKind) },
    hunks = hunkValues.map { (path, header) -> FeatureTaskRuntimeSharedEvidenceHunkEntry(path, header) },
  )
}

private fun resolutionOf(
  stored: StoredEnvelopePayload,
  context: StoredReadContext,
): FeatureTaskRuntimeSharedEvidenceResolution? {
  val index = readStoredIndex(stored.envelope, context.envelopeLabel) ?: return null
  val files = index.files
  val hunks = index.hunks
  val baseRef = stored.envelope.path("base_ref").takeIf { !it.isNull && !it.isMissingNode }?.asText()
  val headRef = stored.envelope.path("head_ref").takeIf { !it.isNull && !it.isMissingNode }?.asText()
  val contractVersion =
    stored.envelope.path("contract_version").asText("").ifBlank {
      FEATURE_TASK_RUNTIME_SHARED_EVIDENCE_PROJECTION_CONTRACT_VERSION
    }
  val fingerprintViolation = FeatureTaskRuntimeSharedEvidenceArtifact.violation(stored.recorded)
  if (fingerprintViolation != null) {
    return degraded(
      seam = "stored_envelope_index",
      used = "re-derive",
      expected = "non-blank file and hunk entries at ${context.envelopeLabel}",
      cause = "IllegalArgumentException: $fingerprintViolation",
    )
  }
  val indexedArtifact =
    FeatureTaskRuntimeSharedEvidenceArtifact(
      fingerprint = stored.recorded,
      baseRef = baseRef,
      headRef = headRef,
      files = files,
      hunks = hunks,
      diffPayload = stored.payloadRef,
    )
  val projection =
    linkedMapOf<String, Any?>(
      SharedPayloadKeys.CONTRACT_VERSION to contractVersion,
      SharedPayloadKeys.WORKFLOW_ID to context.workflowId,
      "repository_checkpoint_fingerprint" to stored.recorded,
      "store_path" to context.storePath,
      "changed_file_count" to files.size,
      "changed_hunk_count" to hunks.size,
      "file_hunk_index_digest" to
        FeatureTaskRuntimeSharedReviewEvidenceReference.fileHunkIndexDigest(indexedArtifact),
    ).apply {
      baseRef?.takeIf { it.isNotBlank() }?.let { put("base_ref", it) }
      headRef?.takeIf { it.isNotBlank() }?.let { put("head_ref", it) }

      if (stored.envelope.has("diff_content") || stored.envelope.has("diff_bytes")) {
        put("diff_content", stored.envelope.path("diff_content").asText("present"))
      }
    }
  FeatureTaskRuntimeSharedEvidenceProjectionSchemaValidator
    .violation(projection, context.envelopeLabel)
    ?.let { reason ->
      return degraded(
        seam = "stored_projection_schema",
        used = "re-derive",
        expected = "schema-valid shared evidence projection at ${context.envelopeLabel}",
        cause = reason,
      )
    }
  return FeatureTaskRuntimeSharedEvidenceResolution(
    artifact = indexedArtifact,
    diffPayload = stored.payloadText,
  )
}

private fun intactPayloadRef(
  artifactDir: Path,
  envelope: ObjectNode,
  envelopeLabel: String,
): FeatureTaskRuntimeSharedEvidenceDiffPayloadRef? {
  val relativePath = envelope.path("diff_payload").path("relative_path").asText("")
  if (relativePath.isBlank()) {
    return degraded(
      "stored_payload_relative_path",
      "re-derive",
      SHARED_EVIDENCE_PAYLOAD_FILE,
      "blank at $envelopeLabel",
    )
  }
  val expectedSize = envelope.path("diff_payload").path("size_bytes").asLong(-1)
  val payload = artifactDir.resolve(relativePath)
  val actualSize = readableSize(payload) ?: return null
  if (expectedSize != actualSize) {
    return degraded("stored_payload_size", "re-derive", "$expectedSize bytes", "truncated to $actualSize bytes")
  }
  return validatedPayloadRef(relativePath, actualSize)
}

private fun validatedPayloadRef(
  relativePath: String,
  actualSize: Long,
): FeatureTaskRuntimeSharedEvidenceDiffPayloadRef? {
  FeatureTaskRuntimeSharedEvidenceDiffPayloadRef.violation(relativePath, actualSize)?.let { reason ->
    return degraded(
      seam = "stored_payload_relative_path",
      used = "re-derive",
      expected = SHARED_EVIDENCE_PAYLOAD_FILE,
      cause = "IllegalArgumentException: $reason",
    )
  }
  return FeatureTaskRuntimeSharedEvidenceDiffPayloadRef(relativePath, actualSize)
}

private fun readPayloadText(payload: Path): String? =
  try {
    Files.readString(payload)
  } catch (error: IOException) {
    degraded(
      seam = "stored_payload_read",
      used = "re-derive",
      expected = "readable payload at $payload",
      cause = "${error::class.simpleName.orEmpty()}: ${error.message.orEmpty()}",
    )
  }

private fun readableSize(payload: Path): Long? =
  try {
    if (Files.isRegularFile(payload)) {
      Files.size(payload)
    } else {
      degraded("stored_payload_file", "re-derive", "regular file at $payload", "absent or not a regular file")
    }
  } catch (error: IOException) {
    degraded(
      seam = "stored_payload_size",
      used = "re-derive",
      expected = "readable size of $payload",
      cause = "${error::class.simpleName.orEmpty()}: ${error.message.orEmpty()}",
    )
  }

private fun readEnvelope(
  mapper: ObjectMapper,
  path: Path,
): ObjectNode? {
  if (!Files.exists(path)) {
    return null
  }
  if (!Files.isRegularFile(path)) {
    return degraded("stored_envelope_file", "re-derive", "regular file at $path", "not a regular file")
  }
  return try {
    mapper.readTree(Files.readString(path)) as? ObjectNode
      ?: degraded("stored_envelope_parse", "re-derive", "JSON object at $path", "parsed to a non-object node")
  } catch (error: IOException) {
    degraded(
      seam = "stored_envelope_parse",
      used = "re-derive",
      expected = "JSON object at $path",
      cause = "${error::class.simpleName.orEmpty()}: ${error.message.orEmpty()}",
    )
  } catch (error: JsonProcessingException) {
    degraded(
      seam = "stored_envelope_parse",
      used = "re-derive",
      expected = "JSON object at $path",
      cause = "${error::class.simpleName.orEmpty()}: ${error.message.orEmpty()}",
    )
  }
}
