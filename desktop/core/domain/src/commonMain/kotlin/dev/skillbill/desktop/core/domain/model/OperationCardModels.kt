package dev.skillbill.desktop.core.domain.model

/** Where one operator approval card of a parked operation stands; only [AwaitingConfirmation] accepts a decision. */
enum class OperationCardState {
  AwaitingConfirmation,
  Confirming,
  Completed,
  Refused,
  Failed,
  Cancelled,
  Superseded,
  ConsumedUncertain,
  ;

  val decidable: Boolean get() = this == AwaitingConfirmation

  val terminal: Boolean get() = this != AwaitingConfirmation && this != Confirming
}

data class OperationCardOptionView(val optionId: String, val label: String, val recommended: Boolean)

data class OperationCardThreadView(val ordinal: String, val threadId: String, val options: List<OperationCardOptionView>)

/** What the operator is asked to approve, taken from the operation's stored proposal. */
sealed interface OperationCardDetailsView {
  data class Verify(
    val criteria: List<String>,
    val intake: String,
    val spec: String?,
    val target: String,
    val mode: String,
    val qa: String,
  ) : OperationCardDetailsView

  data class PrReviewFix(
    val mode: String,
    val pullRequest: String?,
    val push: String?,
    val replies: String?,
    val threads: List<OperationCardThreadView>,
  ) : OperationCardDetailsView
}

/** The durable card of one parked operation, read from the runtime store. [token] is the confirmation authority. */
data class OperationCardView(
  val token: String,
  val launchId: String,
  val operationId: String,
  val state: OperationCardState,
  val summary: String,
  val details: OperationCardDetailsView,
  val selection: String?,
  val evidence: String?,
  val canConfirm: Boolean = false,
  val disabledReason: String? = null,
)

sealed interface OperationCardLookup {
  data object Missing : OperationCardLookup

  data class Present(val card: OperationCardView) : OperationCardLookup

  data class Unavailable(val problem: ReadProblem) : OperationCardLookup
}

/** The operator's decision on a card: [token] confirms it, [selection] is the offered option a PR-review-fix run applies. */
data class OperationCardConfirmation(val token: String, val selection: String? = null)

sealed interface OperationCardDismissOutcome {
  data object Dismissed : OperationCardDismissOutcome

  data class Stale(val reason: String) : OperationCardDismissOutcome
}
