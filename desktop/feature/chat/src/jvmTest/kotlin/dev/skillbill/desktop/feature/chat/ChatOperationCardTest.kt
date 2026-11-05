package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.OperationCardDetailsView
import dev.skillbill.desktop.core.domain.model.OperationCardOptionView
import dev.skillbill.desktop.core.domain.model.OperationCardState
import dev.skillbill.desktop.core.domain.model.OperationCardThreadView
import dev.skillbill.desktop.core.domain.model.OperationCardView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock

class ChatOperationCardTest {
  private fun prReviewFix(state: OperationCardState = OperationCardState.AwaitingConfirmation) = OperationCardView(
    token = "opt-1",
    launchId = "opl-1",
    operationId = "pr-review-fix",
    state = state,
    summary = "matrix",
    details = OperationCardDetailsView.PrReviewFix(
      mode = "local",
      pullRequest = null,
      push = null,
      replies = null,
      threads = listOf(
        OperationCardThreadView("T1", "node-1", listOf(OperationCardOptionView("1", "Fix", true), OperationCardOptionView("2", "Skip", false))),
        OperationCardThreadView("T2", "node-2", listOf(OperationCardOptionView("1", "Fix", true))),
      ),
    ),
    selection = null,
    evidence = null,
    canConfirm = true,
  )

  @Test
  fun `pr-review-fix confirm needs an explicit choice for every thread and never a default`() {
    val thread = ChatThread(Clock.System)
    thread.showOperationCard(prReviewFix())

    assertNull(thread.operationCard("opt-1")!!.selectionWire())
    assertEquals("Choose an option for thread T1 to confirm.", thread.operationCard("opt-1")!!.missingChoiceText())

    thread.chooseOperationOption("opt-1", "T1", "2")
    assertEquals("Choose an option for thread T2 to confirm.", thread.operationCard("opt-1")!!.missingChoiceText())

    thread.chooseOperationOption("opt-1", "T2", "1")
    assertEquals("T1=2,T2=1", thread.operationCard("opt-1")!!.selectionWire())
    assertNull(thread.operationCard("opt-1")!!.missingChoiceText())
  }

  @Test
  fun `a double click claims one decision and a settled card stays inert`() {
    val thread = ChatThread(Clock.System)
    thread.showOperationCard(prReviewFix())

    assertNotNull(thread.beginOperationDecision("opt-1"))
    assertNull(thread.beginOperationDecision("opt-1"))

    thread.showOperationCard(prReviewFix(OperationCardState.Completed))
    thread.showOperationCard(prReviewFix(OperationCardState.AwaitingConfirmation))

    assertEquals(OperationCardState.Completed, thread.operationCard("opt-1")!!.card.state)
    assertNull(thread.beginOperationDecision("opt-1"))
  }

  @Test
  fun `history keeps only the card identity and a restored card is replaced in place by the stored card`() {
    val thread = ChatThread(Clock.System)
    thread.showOperationCard(prReviewFix(OperationCardState.Superseded))

    val line = thread.historyLines().single()
    assertEquals(ChatHistoryRole.OperationCard, line.role)
    assertEquals("opt-1", line.operationCard?.token)

    val restoredThread = ChatThread(Clock.System)
    restoredThread.replaceTranscript(listOf(ChatMessage.Notice("first"), restored(line), ChatMessage.Notice("last")))
    restoredThread.showOperationCard(prReviewFix(OperationCardState.AwaitingConfirmation))

    val shown = restoredThread.transcript.value
    assertEquals(3, shown.size)
    assertEquals(OperationCardState.AwaitingConfirmation, (shown[1] as ChatMessage.OperationCard).card.state)
  }
}
