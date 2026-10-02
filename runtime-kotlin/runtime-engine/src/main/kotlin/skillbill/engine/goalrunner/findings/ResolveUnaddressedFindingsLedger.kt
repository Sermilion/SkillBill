package skillbill.engine.goalrunner.findings

import skillbill.error.shellcontent.InvalidUnaddressedFindingsLedgerSchemaError
import skillbill.goalrunner.model.UnaddressedFindingsLedger

fun resolveUnaddressedFindingsLedger(
  service: UnaddressedFindingsLedgerService?,
  issueKey: String,
): UnaddressedFindingsLedger? {
  if (service == null) return null
  return try {
    service.ledger(issueKey) ?: UnaddressedFindingsLedger(issueKey, emptyList())
  } catch (_: InvalidUnaddressedFindingsLedgerSchemaError) {
    null
  }
}
