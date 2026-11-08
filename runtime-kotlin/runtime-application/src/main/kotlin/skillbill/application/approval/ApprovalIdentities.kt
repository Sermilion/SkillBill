package skillbill.application.approval

import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.ports.approval.model.ApprovalOwnerKey
import skillbill.ports.approval.model.ApprovalRequestIdentity
import java.time.Instant
import java.time.format.DateTimeParseException

internal fun ApprovalRequestRecord.identity() =
  ApprovalRequestIdentity(approvalId, repositoryIdentity, runId, processGeneration, provider, providerRequestId)

internal fun ApprovalRequestRecord.ownerKey() = ApprovalOwnerKey(repositoryIdentity, runId, processGeneration)

internal fun ApprovalRequestIdentity.ownerKey() = ApprovalOwnerKey(repositoryIdentity, runId, processGeneration)

internal fun parseInstant(value: String): Instant? =
  try {
    Instant.parse(value)
  } catch (_: DateTimeParseException) {
    null
  }
