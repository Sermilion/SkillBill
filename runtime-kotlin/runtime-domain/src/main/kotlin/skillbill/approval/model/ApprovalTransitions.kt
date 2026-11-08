package skillbill.approval.model

import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.approval.ApprovalState
import java.time.Instant

fun ApprovalState.settleAs(next: ApprovalState): ApprovalState? =
  if (this == ApprovalState.PENDING && next != ApprovalState.PENDING) next else null

enum class ApprovalTerminalCause(val state: ApprovalState) {
  EXPIRY(ApprovalState.EXPIRED),
  DISMISSAL(ApprovalState.CANCELLED),
  CONSUMER_CLOSED(ApprovalState.CANCELLED),
  PROCESS_END(ApprovalState.PROCESS_ENDED),
  UNSUPPORTED_OWNER(ApprovalState.UNSUPPORTED),
  PROVIDER_DENIAL(ApprovalState.DENIED),
}

fun ApprovalRequestRecord.settleFor(
  cause: ApprovalTerminalCause,
  deadline: Instant,
  now: Instant,
): ApprovalRequestRecord? {
  val reached = now >= deadline
  val next =
    when {
      cause == ApprovalTerminalCause.EXPIRY && !reached -> null
      reached -> ApprovalState.EXPIRED
      else -> cause.state
    }
  return next?.let { target ->
    state.settleAs(target)?.let {
      copy(state = target, decisionSource = null, selectedOptionId = null, ruleId = null)
    }
  }
}
