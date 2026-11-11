package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.ApprovalCardKey
import dev.skillbill.desktop.core.domain.model.ApprovalDecisionOutcome
import dev.skillbill.desktop.core.domain.model.ApprovalFeed
import dev.skillbill.desktop.core.domain.model.ApprovalRevokeOutcome
import dev.skillbill.desktop.core.domain.model.ApprovalRulesResult
import kotlinx.coroutines.flow.Flow

/**
 * The selected runtime's approval feed. Collecting [observe] is what makes the operator surface live: the session
 * registers its consumer while a collector exists and releases it, cancelling outstanding requests, when the last one leaves.
 */
interface ApprovalFeedRepository {
  fun observe(repositoryRoot: String, retainedKeys: Flow<Set<ApprovalCardKey>>): Flow<ApprovalFeed>

  suspend fun decide(key: ApprovalCardKey, optionId: String, alwaysAllow: Boolean, pattern: String? = null): ApprovalDecisionOutcome

  suspend fun dismiss(key: ApprovalCardKey): ApprovalDecisionOutcome

  /** The durable rules of the repository enclosing [repositoryRoot]; listing selects and grants nothing. */
  suspend fun rules(repositoryRoot: String): ApprovalRulesResult

  /** Revokes one rule. It prevents later automatic grants and never undoes an action already authorized. */
  suspend fun revokeRule(repositoryRoot: String, ruleId: String): ApprovalRevokeOutcome
}
