package skillbill.application.approval

import skillbill.approval.model.ApprovalGrantRefusal
import skillbill.approval.model.validateApprovalPattern
import skillbill.ports.approval.model.ApprovalStoredRequest

internal fun ApprovalStoredRequest.patternRefusal(pattern: String): ApprovalGrantRefusal? {
  val matchFacts = facts.matchFacts ?: return ApprovalGrantRefusal.NO_MATCH_FACTS
  return validateApprovalPattern(pattern.trim(), matchFacts)
}
