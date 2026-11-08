package dev.skillbill.desktop.core.data.approval

import dev.skillbill.desktop.core.domain.model.ApprovalCard
import dev.skillbill.desktop.core.domain.model.ApprovalCardGrants
import dev.skillbill.desktop.core.domain.model.ApprovalCardKey
import dev.skillbill.desktop.core.domain.model.ApprovalCardOption
import dev.skillbill.desktop.core.domain.model.ApprovalCardScope
import dev.skillbill.desktop.core.domain.model.ApprovalDecisionOutcome
import dev.skillbill.desktop.core.domain.model.ApprovalRule
import skillbill.application.approval.model.ApprovalOperatorView
import skillbill.approval.model.ApprovalGrantRefusal
import skillbill.contracts.approval.ApprovalProviderRequestId
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.approval.ApprovalRuleRecord
import skillbill.ports.approval.model.ApprovalDecisionResult
import skillbill.ports.approval.model.ApprovalRequestIdentity

/** Copies every stored field; nothing is clipped, summarized or reconstructed. Grants stay unknown for a bare record. */
internal fun ApprovalRequestRecord.toCard(): ApprovalCard {
  val (requestId, isInteger) = when (val id = providerRequestId) {
    is ApprovalProviderRequestId.Text -> id.value to false
    is ApprovalProviderRequestId.Integer -> id.value to true
  }
  return ApprovalCard(
    key = ApprovalCardKey(approvalId, repositoryIdentity, runId, processGeneration, provider, requestId, isInteger),
    checkoutRoot = checkoutRoot,
    providerSessionId = providerSessionId,
    title = title,
    reason = reason,
    subject = subject,
    inputText = inputText,
    options = options.map { ApprovalCardOption(it.optionId, it.label, it.kind) },
    actionFingerprint = actionFingerprint,
    authorityScope = authorityScope?.let {
      ApprovalCardScope(it.profileIdentity, it.protocolRevision, it.actionKind, it.workingDirectory, it.actionFingerprint)
    },
    createdAt = createdAt,
    deadlineAt = deadlineAt,
    state = state.wireValue,
    decisionSource = decisionSource?.wireValue,
    selectedOptionId = selectedOptionId,
    ruleId = ruleId,
    providerPayload = providerPayload,
    repositoryName = checkoutRoot.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\'),
  )
}

/** The record plus the runtime's own delivery progress, provider disposition and grant eligibility. */
internal fun ApprovalOperatorView.toCard(): ApprovalCard = record.toCard().copy(
  delivery = delivery?.name?.lowercase(),
  providerDisposition = providerDisposition,
  grants = ApprovalCardGrants(
    oneTimeUnavailable = unavailableReason(oneTimeRefusal),
    alwaysAllowUnavailable = unavailableReason(alwaysAllowRefusal),
  ),
)

private fun ApprovalOperatorView.unavailableReason(refusal: ApprovalGrantRefusal?): String? = when {
  !ownerLive && record.state.wireValue == "pending" -> NOT_RUNNING_REASON
  refusal == null -> null
  else -> refusal.readableReason()
}

internal fun ApprovalGrantRefusal.readableReason(): String = when (this) {
  ApprovalGrantRefusal.PROVIDER_DENIED -> "The provider denied this request, so it cannot be allowed here."
  ApprovalGrantRefusal.PROVIDER_INTERACTION_REQUIRED -> "The provider requires its own interaction for this request."
  ApprovalGrantRefusal.NO_ONE_TIME_GRANT -> "The provider offered no one-time allow option."
  ApprovalGrantRefusal.READ_ONLY_WRITE_ACTION -> "Read-only chat: write actions cannot be allowed."
  ApprovalGrantRefusal.NOT_ORDINARY_ACTION ->
    "Sandbox or network escalations and other special requests cannot be saved as repository rules."
  ApprovalGrantRefusal.INCOMPLETE_INPUT -> "Not eligible for a repository rule: the action input is incomplete."
  ApprovalGrantRefusal.INCOMPLETE_SCOPE -> "Not eligible for a repository rule: the action scope is incomplete."
}

private const val NOT_RUNNING_REASON = "The requesting process is no longer running."

internal fun ApprovalRuleRecord.toRule(): ApprovalRule = ApprovalRule(
  ruleId, provider, profileIdentity, protocolRevision, actionKind, workingDirectory, actionFingerprint, createdAt,
)

internal fun ApprovalCardKey.toIdentity(): ApprovalRequestIdentity = ApprovalRequestIdentity(
  approvalId = approvalId,
  repositoryIdentity = repositoryIdentity,
  runId = runId,
  processGeneration = processGeneration,
  provider = provider,
  providerRequestId = if (providerRequestIdIsInteger) {
    ApprovalProviderRequestId.Integer(providerRequestId)
  } else {
    ApprovalProviderRequestId.Text(providerRequestId)
  },
)

internal fun ApprovalDecisionResult.toOutcome(): ApprovalDecisionOutcome = when (this) {
  is ApprovalDecisionResult.Settled -> ApprovalDecisionOutcome.Settled
  is ApprovalDecisionResult.AlreadySettled -> ApprovalDecisionOutcome.AlreadySettled
  is ApprovalDecisionResult.NotLive -> ApprovalDecisionOutcome.NotLive
  is ApprovalDecisionResult.OptionUnavailable -> ApprovalDecisionOutcome.Refused("The provider did not offer that option.")
  is ApprovalDecisionResult.GrantRefused -> ApprovalDecisionOutcome.Refused(refusal.readableReason())
  is ApprovalDecisionResult.InvalidRecord ->
    ApprovalDecisionOutcome.Refused("The stored approval could not be read, so it was not decided.")
  is ApprovalDecisionResult.DeadlineNotReached -> ApprovalDecisionOutcome.Refused("The approval has not reached its deadline.")
  ApprovalDecisionResult.Missing -> ApprovalDecisionOutcome.Refused("The approval request no longer exists.")
}
