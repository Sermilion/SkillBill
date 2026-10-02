package skillbill.application.diagnostics

import skillbill.application.diagnostics.model.RejectedOutputDiagnosticConfig
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticDeletion
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRawRead
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRecording
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticSelection
import skillbill.error.core.RejectedOutputDiagnosticFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rejectedOutputDiagnosticCorruptMessage
import skillbill.ports.diagnostics.ProducerOutputEvidenceValidator
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.diagnostics.RejectedOutputDiagnosticPermissions
import skillbill.ports.diagnostics.RejectedOutputDiagnosticRepository
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticInsert
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticRead
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticRecord
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticSelector
import skillbill.ports.diagnostics.model.RejectedOutputLifecycle
import skillbill.text.RECORD_FIELD_SEPARATOR
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

class RejectedOutputDiagnosticService(
  private val repository: RejectedOutputDiagnosticRepository,
  private val permissions: RejectedOutputDiagnosticPermissions,
  private val metadataValidator: RejectedOutputDiagnosticMetadataValidator,
  private val config: RejectedOutputDiagnosticConfig = RejectedOutputDiagnosticConfig(),
  private val clock: Clock,
  private val producerEvidenceValidator: ProducerOutputEvidenceValidator = { },
) {
  fun record(request: RejectedOutputDiagnosticRequest): RejectedOutputDiagnosticRecording {
    requestValidationIssue(request)?.let { reason ->
      return RejectedOutputDiagnosticRecording.InvalidRequest(reason)
    }
    val identity = stableIdentity(request.workflowId, request.phaseId, request.attempt, request.repairTurn)
    val existing = existing(identity)
    return if (existing == null) {
      recordNew(request, identity)
    } else if (existing.matches(request)) {
      metadataValidator.validate(existing.metadata)
      RejectedOutputDiagnosticRecording.Recorded(existing.metadata)
    } else {
      RejectedOutputDiagnosticRecording.Conflict(identity)
    }
  }

  private fun recordNew(
    request: RejectedOutputDiagnosticRequest,
    identity: String,
  ): RejectedOutputDiagnosticRecording {
    cleanup()
    val oversized = request.truncated || request.observedByteSize > config.maximumPayloadBytes
    val metadata =
      RejectedOutputDiagnostic(
        identity = identity,
        workflowId = request.workflowId,
        phaseId = request.phaseId,
        attempt = request.attempt,
        rule = request.rule,
        path = request.path,
        reason = request.reason,
        agentId = request.agentId,
        model = request.model,
        recordedAt = clock.instant(),
        byteSize = request.observedByteSize,
        sha256 = request.observedSha256,
        lifecycle = if (oversized) RejectedOutputLifecycle.OVERSIZED else RejectedOutputLifecycle.STORED,
        repairTurn = request.repairTurn,
      )
    metadataValidator.validate(metadata)
    applyRestrictivePermissions()
    return when (
      val inserted =
        repository.insert(
          RejectedOutputDiagnosticRecord(metadata, request.rawResponse.takeUnless { oversized }),
        )
    ) {
      is RejectedOutputDiagnosticInsert.Inserted ->
        RejectedOutputDiagnosticRecording.Recorded(inserted.record.metadata)
      is RejectedOutputDiagnosticInsert.Conflict -> RejectedOutputDiagnosticRecording.Conflict(inserted.identity)
    }
  }

  fun retainProducerOutput(evidence: ProducerOutputEvidence) {
    producerEvidenceValidator.validate(evidence)
    applyRestrictivePermissions()
    cleanup()
    repository.retainProducerOutput(evidence)
  }

  private fun applyRestrictivePermissions() {
    try {
      permissions.applyRestrictivePermissions()
    } catch (error: IOException) {
      permissionFailure(error)
    } catch (error: SecurityException) {
      permissionFailure(error)
    } catch (error: UnsupportedOperationException) {
      permissionFailure(error)
    }
  }

  fun inspect(selector: RejectedOutputDiagnosticSelector): RejectedOutputDiagnosticSelection {
    selectorValidationIssue(selector)?.let { reason ->
      return RejectedOutputDiagnosticSelection.InvalidRequest(reason)
    }
    cleanup()
    return RejectedOutputDiagnosticSelection.Selected(
      repository.select(selector).onEach(metadataValidator::validate),
    )
  }

  fun readRaw(identity: String): RejectedOutputDiagnosticRawRead {
    cleanup()
    val record =
      when (val read = repository.read(identity)) {
        is RejectedOutputDiagnosticRead.Absent -> return RejectedOutputDiagnosticRawRead.Absent(read.identity)
        is RejectedOutputDiagnosticRead.Found -> read.record
        is RejectedOutputDiagnosticRead.Expired -> read.record
        is RejectedOutputDiagnosticRead.Oversized -> read.record
      }
    metadataValidator.validate(record.metadata)
    return when (record.metadata.lifecycle) {
      RejectedOutputLifecycle.EXPIRED -> RejectedOutputDiagnosticRawRead.Expired(record.metadata.identity)
      RejectedOutputLifecycle.OVERSIZED -> RejectedOutputDiagnosticRawRead.Oversized(record.metadata.identity)
      RejectedOutputLifecycle.STORED -> RejectedOutputDiagnosticRawRead.Payload(verifiedPayload(record))
    }
  }

  fun cleanup(now: Instant = clock.instant()): Int {
    val cutoff = now.minus(config.retention)
    return repository.markExpired(cutoff) + repository.deleteProducerOutputsBefore(cutoff)
  }

  fun delete(selector: RejectedOutputDiagnosticSelector): RejectedOutputDiagnosticDeletion =
    selectorValidationIssue(selector)?.let { reason -> RejectedOutputDiagnosticDeletion.InvalidRequest(reason) }
      ?: RejectedOutputDiagnosticDeletion.Deleted(repository.delete(selector))

  private fun existing(identity: String): RejectedOutputDiagnosticRecord? =
    when (val read = repository.read(identity)) {
      is RejectedOutputDiagnosticRead.Found -> read.record
      is RejectedOutputDiagnosticRead.Expired -> read.record
      is RejectedOutputDiagnosticRead.Oversized -> read.record
      is RejectedOutputDiagnosticRead.Absent -> null
    }

  private fun selectorValidationIssue(selector: RejectedOutputDiagnosticSelector): String? =
    when {
      selector.workflowId.isBlank() -> "workflowId must be non-blank"
      selector.phaseId?.isBlank() == true -> "phaseId must be non-blank when present"
      selector.attempt?.let { it <= 0 } == true -> "attempt must be positive when present"
      else -> null
    }

  companion object {
    fun stableIdentity(
      workflowId: String,
      phaseId: String,
      attempt: Int,
      repairTurn: Int = 0,
    ): String {
      val base = "$workflowId$RECORD_FIELD_SEPARATOR$phaseId$RECORD_FIELD_SEPARATOR$attempt"
      val preimage = if (repairTurn == 0) base else "$base$RECORD_FIELD_SEPARATOR$repairTurn"
      return "rod_${sha256(preimage.encodeToByteArray())}"
    }

    fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }
}

private fun verifiedPayload(record: RejectedOutputDiagnosticRecord): ByteArray {
  val payload = record.payload ?: corruptDiagnostic(record.metadata.identity)
  if (payload.size.toLong() != record.metadata.byteSize ||
    RejectedOutputDiagnosticService.sha256(payload) != record.metadata.sha256
  ) {
    corruptDiagnostic(record.metadata.identity)
  }
  return payload
}

private fun corruptDiagnostic(identity: String): Nothing =
  throw SkillBillRuntimeException(
    RejectedOutputDiagnosticFailureCode.CORRUPT,
    rejectedOutputDiagnosticCorruptMessage(identity),
  )

private fun requestValidationIssue(request: RejectedOutputDiagnosticRequest): String? {
  val required =
    mapOf(
      "workflowId" to request.workflowId,
      "phaseId" to request.phaseId,
      "rule" to request.rule,
      "path" to request.path,
      "reason" to request.reason,
      "agentId" to request.agentId,
      "model" to request.model,
    )
  val blankField = required.entries.firstOrNull { it.value.isBlank() }?.key
  return when {
    blankField != null -> "$blankField must be non-blank"
    request.attempt <= 0 -> "attempt must be positive"
    request.repairTurn < 0 -> "repairTurn must be non-negative"
    request.observedByteSize < request.rawResponse.size || request.observedByteSize < 0 ->
      "observedByteSize must include all retained bytes"
    !Regex("[0-9a-f]{64}").matches(request.observedSha256) ->
      "observedSha256 must be a lowercase SHA-256 digest"
    !request.truncated &&
      (
        request.observedByteSize != request.rawResponse.size.toLong() ||
          request.observedSha256 != RejectedOutputDiagnosticService.sha256(request.rawResponse)
      )
    -> "complete response evidence does not match its bytes"
    else -> null
  }
}

private fun permissionFailure(error: Throwable): Nothing =
  throw SkillBillRuntimeException(
    RejectedOutputDiagnosticFailureCode.PERMISSION,
    "Rejected output diagnostic permission operation 'apply' failed.",
    error,
  )

private fun RejectedOutputDiagnosticRecord.matches(request: RejectedOutputDiagnosticRequest): Boolean =
  metadata.workflowId == request.workflowId &&
    metadata.phaseId == request.phaseId &&
    metadata.attempt == request.attempt &&
    metadata.repairTurn == request.repairTurn &&
    metadata.rule == request.rule &&
    metadata.path == request.path &&
    metadata.reason == request.reason &&
    metadata.agentId == request.agentId &&
    metadata.model == request.model &&
    metadata.byteSize == request.observedByteSize &&
    metadata.sha256 == request.observedSha256
