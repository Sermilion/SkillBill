package dev.skillbill.desktop.core.domain.model

/** One provider choice on an approval card, in the provider's original order. */
data class ApprovalCardOption(val optionId: String, val label: String, val kind: String)

/** The exact matching dimensions a durable rule would use; null when the provider supplied no trustworthy scope. */
data class ApprovalCardScope(
  val profileIdentity: String,
  val protocolRevision: String,
  val actionKind: String,
  val workingDirectory: String,
  val actionFingerprint: String,
)

/** Identifies one pending request by its full correlation key; [providerRequestIdIsInteger] preserves the provider's id type. */
data class ApprovalCardKey(
  val approvalId: String,
  val repositoryIdentity: String,
  val runId: String,
  val processGeneration: String,
  val provider: String,
  val providerRequestId: String,
  val providerRequestIdIsInteger: Boolean,
)

/** Provider option kind for a remembering choice (provider-global or session-wide). It is shown but never selectable. */
const val PROVIDER_REMEMBER_OPTION_KIND = "provider_remember"

/**
 * Why a grant is unavailable, as the runtime's own policy decided it; null means available. This is the runtime's answer
 * for a live card and is never saved: a restored card has no grants, so history can never enable a control.
 */
data class ApprovalCardGrants(
  val oneTimeUnavailable: String?,
  val alwaysAllowUnavailable: String?,
  /** Why a pattern-rule grant is unavailable; null when eligible. */
  val similarUnavailable: String? = null,
  /** Runtime-suggested pattern prefill for the similar editor; null when unavailable. */
  val suggestedPattern: String? = null,
)

/** The complete stored approval record, never clipped; display and controls belong to the card surface. */
data class ApprovalCard(
  val key: ApprovalCardKey,
  val checkoutRoot: String,
  val providerSessionId: String?,
  val title: String,
  val reason: String?,
  val subject: String,
  val inputText: String,
  val options: List<ApprovalCardOption>,
  val actionFingerprint: String?,
  val authorityScope: ApprovalCardScope?,
  val createdAt: String,
  val deadlineAt: String,
  val state: String,
  val decisionSource: String?,
  val selectedOptionId: String?,
  val ruleId: String?,
  val providerPayload: String,
  /** Wire value of the provider response progress (`claimed`, `written`, `uncertain`); null before any claim. */
  val delivery: String? = null,
  /** `none`, `denied` or `interaction_required`: what the provider decided, apart from the local decision. */
  val providerDisposition: String = "none",
  /** The runtime's grant eligibility for a live card; null when not yet known, which keeps every grant disabled. */
  val grants: ApprovalCardGrants? = null,
  val repositoryName: String = "",
  /** Pattern of the durable rule that decided this card, when applicable. */
  val rulePattern: String? = null,
) {
  val isPending: Boolean get() = state == "pending"
}

/** One durable repository rule: the exact dimensions an Always allow decision matched on. */
data class ApprovalRule(
  val ruleId: String,
  val provider: String,
  val profileIdentity: String,
  val protocolRevision: String,
  val actionKind: String,
  val workingDirectory: String,
  val actionFingerprint: String,
  val createdAt: String,
  /** Wildcard pattern when this is a pattern rule; null for exact rules. */
  val pattern: String? = null,
)

sealed interface ApprovalRulesResult {
  data class Listed(val rules: List<ApprovalRule>) : ApprovalRulesResult

  data class Unavailable(val reason: String) : ApprovalRulesResult
}

sealed interface ApprovalRevokeOutcome {
  data object Revoked : ApprovalRevokeOutcome

  data class NotFound(val reason: String) : ApprovalRevokeOutcome

  data class Failed(val reason: String) : ApprovalRevokeOutcome
}

/** The selected runtime's approval feed for one repository. */
sealed interface ApprovalFeed {
  /** No database session, or the feed cannot be read; [reason] says why. No card can be answered. */
  data class Unavailable(val reason: String) : ApprovalFeed

  /** [live] is true only while this session holds a verified operator consumer registration for the repository. */
  data class Observing(val live: Boolean, val cards: List<ApprovalCard>) : ApprovalFeed
}

sealed interface ApprovalDecisionOutcome {
  data object Settled : ApprovalDecisionOutcome

  data object AlreadySettled : ApprovalDecisionOutcome

  /** No live operator registration for this session holds authority; nothing was written. */
  data object NotLive : ApprovalDecisionOutcome

  data class Refused(val reason: String) : ApprovalDecisionOutcome
}
