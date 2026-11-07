package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ApprovalCard
import dev.skillbill.desktop.core.domain.model.ApprovalCardKey
import dev.skillbill.desktop.core.domain.model.ApprovalDecisionOutcome
import dev.skillbill.desktop.core.domain.model.ApprovalFeed
import dev.skillbill.desktop.core.domain.model.ApprovalRevokeOutcome
import dev.skillbill.desktop.core.domain.model.ApprovalRulesResult
import dev.skillbill.desktop.core.domain.repository.ApprovalFeedRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal const val APPROVAL_NOT_LIVE_NOTE =
  "This window does not hold the operator registration for this repository, so nothing was sent."

/**
 * The feed of a workspace built without one: cards show with every control disabled and nothing can be decided, so the
 * absence of a feed never grants anything.
 */
internal object UnavailableApprovalFeed : ApprovalFeedRepository {
  private const val REASON = "No approval feed is connected, so no approval card can be answered."

  override fun observe(repositoryRoot: String, retainedKeys: Flow<Set<ApprovalCardKey>>): Flow<ApprovalFeed> =
    flowOf(ApprovalFeed.Unavailable(REASON))

  override suspend fun decide(key: ApprovalCardKey, optionId: String, alwaysAllow: Boolean): ApprovalDecisionOutcome =
    ApprovalDecisionOutcome.NotLive

  override suspend fun dismiss(key: ApprovalCardKey): ApprovalDecisionOutcome = ApprovalDecisionOutcome.NotLive

  override suspend fun rules(repositoryRoot: String): ApprovalRulesResult = ApprovalRulesResult.Unavailable(REASON)

  override suspend fun revokeRule(repositoryRoot: String, ruleId: String): ApprovalRevokeOutcome = ApprovalRevokeOutcome.Failed(REASON)
}

/**
 * Connects one chat thread to the repository's approval feed and sends its explicit clicks. The feed supplies the runtime's
 * own answer for each card: grant eligibility and the current outcome. Only a card the feed reports as live, with a
 * matching registration, turns a click into a decision; the runtime refuses everything else without writing.
 */
internal class ChatApprovals(private val repository: ApprovalFeedRepository) {
  /**
   * Shows the repository's cards of [thread] as the feed changes: the requests [owns] accepts and every card the
   * transcript already holds, so a restored card also receives its final outcome. [live] follows the feed's registration
   * and is false whenever the feed is unavailable or this collector ends. [changed] runs after an outcome changed.
   */
  suspend fun follow(
    thread: ChatThread,
    live: MutableStateFlow<Boolean>,
    repositoryRoot: () -> String,
    owns: (ApprovalCard) -> Boolean,
    changed: suspend () -> Unit,
  ) {
    try {
      val retainedKeys = thread.transcript.map { messages ->
        messages.mapNotNull { (it as? ChatMessage.Approval)?.card?.key }.toSet()
      }
      repository.observe(repositoryRoot(), retainedKeys).collect { feed ->
        when (feed) {
          is ApprovalFeed.Unavailable -> live.value = false
          is ApprovalFeed.Observing -> {
            var outcomeChanged = false
            feed.cards.forEach { card ->
              val known = thread.approval(card.key)
              if (known == null && !owns(card)) return@forEach
              thread.showApproval(card)
              if (known == null || known.state != card.state) outcomeChanged = true
            }
            live.value = feed.live
            if (outcomeChanged) changed()
          }
        }
      }
    } finally {
      live.value = false
    }
  }

  /** Sends the operator's click on [optionId]; the card shows as submitting until the feed reports its new outcome. */
  suspend fun decide(thread: ChatThread, key: ApprovalCardKey, optionId: String, alwaysAllow: Boolean) {
    val card = thread.beginApprovalDecision(key) ?: return
    val outcome = try {
      repository.decide(card.key, optionId, alwaysAllow)
    } catch (cancelled: CancellationException) {
      thread.approvalSubmitting(key, submitting = false)
      throw cancelled
    }
    settle(thread, key, outcome)
  }

  /** Dismisses the pending request without choosing an option. */
  suspend fun dismiss(thread: ChatThread, key: ApprovalCardKey) {
    val card = thread.beginApprovalDecision(key) ?: return
    val outcome = try {
      repository.dismiss(card.key)
    } catch (cancelled: CancellationException) {
      thread.approvalSubmitting(key, submitting = false)
      throw cancelled
    }
    settle(thread, key, outcome)
  }

  private fun settle(thread: ChatThread, key: ApprovalCardKey, outcome: ApprovalDecisionOutcome) {
    when (outcome) {
      // The feed reports the settled card next; that update ends the submitting state.
      ApprovalDecisionOutcome.Settled, ApprovalDecisionOutcome.AlreadySettled -> Unit
      ApprovalDecisionOutcome.NotLive -> thread.approvalSubmitting(key, submitting = false, refusal = APPROVAL_NOT_LIVE_NOTE)
      is ApprovalDecisionOutcome.Refused -> thread.approvalSubmitting(key, submitting = false, refusal = outcome.reason)
    }
  }
}
