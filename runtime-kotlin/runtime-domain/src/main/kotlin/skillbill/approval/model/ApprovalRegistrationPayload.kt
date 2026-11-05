package skillbill.approval.model

import skillbill.contracts.approval.ApprovalRequestRecord

fun sameApprovalRegistrationPayload(
  existing: ApprovalRequestRecord,
  incoming: ApprovalRequestRecord,
): Boolean =
  existing.copy(
    createdAt = incoming.createdAt,
    deadlineAt = incoming.deadlineAt,
    state = incoming.state,
    decisionSource = incoming.decisionSource,
    selectedOptionId = incoming.selectedOptionId,
    ruleId = incoming.ruleId,
  ) == incoming
