package skillbill.ports.approval

import skillbill.contracts.approval.ApprovalRuleRecord

/** Repository-scoped approval rules sharing the caller's transaction with decisions and revocation. */
interface ApprovalRuleStore {
  /** Lists active rules for one canonical repository identity. */
  fun activeRules(repositoryIdentity: String): List<ApprovalRuleRecord>

  /** Revokes one active rule for its owning repository identity. */
  fun revokeRule(
    ruleId: String,
    repositoryIdentity: String,
    revokedAt: String,
  ): Boolean
}
