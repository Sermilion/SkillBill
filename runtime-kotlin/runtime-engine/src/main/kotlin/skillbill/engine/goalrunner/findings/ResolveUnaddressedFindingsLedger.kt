package skillbill.engine.goalrunner.findings

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.GovernedReviewFailureCode
import skillbill.goalrunner.model.UnaddressedFindingsLedger

fun resolveUnaddressedFindingsLedger(
  service: UnaddressedFindingsLedgerService?,
  issueKey: String,
): UnaddressedFindingsLedger? {
  if (service == null) return null
  return try {
    service.ledger(issueKey) ?: UnaddressedFindingsLedger(issueKey, emptyList())
  } catch (error: SkillBillRuntimeException) {
    error.rethrowUnless(error.code == GovernedReviewFailureCode.INVALID_LEDGER_SCHEMA)
    null
  }
}
