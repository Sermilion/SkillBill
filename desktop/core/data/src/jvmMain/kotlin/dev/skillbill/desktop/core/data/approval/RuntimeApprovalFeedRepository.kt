package dev.skillbill.desktop.core.data.approval

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.repository.sessionFlow
import dev.skillbill.desktop.core.data.session.DatabaseSessionComponent
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.domain.model.ApprovalCardKey
import dev.skillbill.desktop.core.domain.model.ApprovalDecisionOutcome
import dev.skillbill.desktop.core.domain.model.ApprovalFeed
import dev.skillbill.desktop.core.domain.model.ApprovalRevokeOutcome
import dev.skillbill.desktop.core.domain.model.ApprovalRulesResult
import dev.skillbill.desktop.core.domain.repository.ApprovalFeedRepository
import kotlinx.coroutines.flow.Flow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Reads the approval feed through the open database session's component. A database switch cancels the old session's
 * collection, which releases its operator registration, and subscribes on the new session. A decision made after the
 * session it was shown in is no longer the current one is refused without writing.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RuntimeApprovalFeedRepository(
  private val sessions: DatabaseSessionManager,
  private val diagnostics: Diagnostics,
) : ApprovalFeedRepository {
  override fun observe(repositoryRoot: String, retainedKeys: Flow<Set<ApprovalCardKey>>): Flow<ApprovalFeed> = sessions.sessionFlow(
    opening = ApprovalFeed.Unavailable("The database session is opening, so no approval card can be answered."),
    failed = {
      diagnostics.record("approval.feed_unavailable")
      ApprovalFeed.Unavailable("The database is unavailable, so no approval card can be answered.")
    },
  ) { it.approvals.observe(repositoryRoot, retainedKeys) }

  override suspend fun decide(key: ApprovalCardKey, optionId: String, alwaysAllow: Boolean, pattern: String?): ApprovalDecisionOutcome =
    current()?.approvals?.decide(key, optionId, alwaysAllow, pattern) ?: ApprovalDecisionOutcome.NotLive

  override suspend fun dismiss(key: ApprovalCardKey): ApprovalDecisionOutcome =
    current()?.approvals?.dismiss(key) ?: ApprovalDecisionOutcome.NotLive

  override suspend fun rules(repositoryRoot: String): ApprovalRulesResult =
    current()?.approvals?.rules(repositoryRoot) ?: ApprovalRulesResult.Unavailable(NO_SESSION_REASON)

  override suspend fun revokeRule(repositoryRoot: String, ruleId: String): ApprovalRevokeOutcome =
    current()?.approvals?.revokeRule(repositoryRoot, ruleId) ?: ApprovalRevokeOutcome.Failed(NO_SESSION_REASON)

  private companion object {
    const val NO_SESSION_REASON = "No database session is open, so repository rules are unavailable."
  }

  private fun current(): DatabaseSessionComponent? = sessions.sessions.value?.takeIf(sessions::isCurrent)
}
