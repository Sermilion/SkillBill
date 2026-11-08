package dev.skillbill.desktop.core.data.control

import dev.skillbill.desktop.core.domain.model.OperationCardDetailsView
import dev.skillbill.desktop.core.domain.model.OperationCardOptionView
import dev.skillbill.desktop.core.domain.model.OperationCardState
import dev.skillbill.desktop.core.domain.model.OperationCardThreadView
import dev.skillbill.desktop.core.domain.model.OperationCardView
import skillbill.contracts.operationcard.OperationCardDetails
import skillbill.contracts.operationcard.OperationCardProjection
import skillbill.contracts.operationcard.OperationCardStatus

internal fun OperationCardProjection.toView(canConfirm: Boolean = false, disabledReason: String? = null): OperationCardView =
  OperationCardView(
    token = token,
    launchId = launchId,
    operationId = operationId,
    state = status.toState(),
    summary = fullSummary,
    details = proposalDetails.toView(),
    selection = selection,
    evidence = evidence,
    canConfirm = canConfirm && status == OperationCardStatus.AWAITING_CONFIRMATION,
    disabledReason = disabledReason,
  )

private fun OperationCardStatus.toState(): OperationCardState =
  when (this) {
    OperationCardStatus.AWAITING_CONFIRMATION -> OperationCardState.AwaitingConfirmation
    OperationCardStatus.CONFIRMING -> OperationCardState.Confirming
    OperationCardStatus.COMPLETED -> OperationCardState.Completed
    OperationCardStatus.REFUSED -> OperationCardState.Refused
    OperationCardStatus.FAILED -> OperationCardState.Failed
    OperationCardStatus.CANCELLED -> OperationCardState.Cancelled
    OperationCardStatus.SUPERSEDED -> OperationCardState.Superseded
    OperationCardStatus.CONSUMED_UNCERTAIN -> OperationCardState.ConsumedUncertain
  }

private fun OperationCardDetails.toView(): OperationCardDetailsView =
  when (this) {
    is OperationCardDetails.Verify -> OperationCardDetailsView.Verify(criteria, intake, spec, target, mode, qa)
    is OperationCardDetails.PrReviewFix ->
      OperationCardDetailsView.PrReviewFix(
        mode,
        pullRequest,
        push,
        replies,
        threads.map { thread ->
          OperationCardThreadView(
            thread.ordinal,
            thread.threadId,
            thread.options.map { OperationCardOptionView(it.optionId, it.label, it.recommended) },
          )
        },
      )
  }
