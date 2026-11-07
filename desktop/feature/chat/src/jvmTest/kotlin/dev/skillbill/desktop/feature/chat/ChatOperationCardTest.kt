package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.NewTaskKind
import dev.skillbill.desktop.core.domain.model.NewTaskStart
import dev.skillbill.desktop.core.domain.model.NewTaskStartOutcome
import dev.skillbill.desktop.core.domain.model.OperationCardDetailsView
import dev.skillbill.desktop.core.domain.model.OperationCardConfirmation
import dev.skillbill.desktop.core.domain.model.OperationCardDismissOutcome
import dev.skillbill.desktop.core.domain.model.OperationCardOptionView
import dev.skillbill.desktop.core.domain.model.OperationCardState
import dev.skillbill.desktop.core.domain.model.OperationCardThreadView
import dev.skillbill.desktop.core.domain.model.OperationCardView
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock

class ChatOperationCardTest {
  private fun verify(canConfirm: Boolean = true) = OperationCardView(
    token = "verify-token",
    launchId = "verify-launch",
    operationId = "verify",
    state = OperationCardState.AwaitingConfirmation,
    summary = "verification",
    details = OperationCardDetailsView.Verify(emptyList(), "SKILL-453", null, "SKILL-453", "local", "full"),
    selection = null,
    evidence = null,
    canConfirm = canConfirm,
    disabledReason = "The source anchors changed.",
  )

  private fun prReviewFix(
    state: OperationCardState = OperationCardState.AwaitingConfirmation,
    recommendations: Boolean = true,
  ) = OperationCardView(
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
        OperationCardThreadView(
          "T1",
          "node-1",
          listOf(OperationCardOptionView("1", "Fix", recommendations), OperationCardOptionView("2", "Skip", false)),
        ),
        OperationCardThreadView("T2", "node-2", listOf(OperationCardOptionView("1", "Fix", recommendations))),
      ),
    ),
    selection = null,
    evidence = null,
    canConfirm = true,
  )

  @Test
  fun `pr-review-fix confirm accepts an explicit offered subset and rejects an empty selection`() {
    val thread = ChatThread(Clock.System)
    thread.showOperationCard(prReviewFix())

    assertNull(thread.operationCard("opt-1")!!.selectionWire())
    assertEquals(
      "Choose an option for at least one thread to confirm.",
      thread.operationCard("opt-1")!!.missingChoiceText(),
    )

    thread.chooseOperationOption("opt-1", "T1", "2")
    assertEquals("T1=2", thread.operationCard("opt-1")!!.selectionWire())
    assertNull(thread.operationCard("opt-1")!!.missingChoiceText())

    thread.chooseOperationOption("opt-1", "T2", "1")
    assertEquals("T1=2,T2=1", thread.operationCard("opt-1")!!.selectionWire())
    assertNull(thread.operationCard("opt-1")!!.missingChoiceText())
  }

  @Test
  fun `history restores exactly the saved subset and keeps an explicit empty draft distinct`() {
    val selected = ChatThread(Clock.System)
    selected.showOperationCard(prReviewFix())
    selected.chooseOperationOption("opt-1", "T1", "2")
    val selectedLine = selected.historyLines().single()
    assertEquals(mapOf("T1" to "2"), selectedLine.operationCard?.draftChoices)

    val restoredSubset = ChatThread(Clock.System)
    restoredSubset.replaceTranscript(listOf(restored(selectedLine)))
    restoredSubset.showOperationCard(prReviewFix())
    assertEquals("T1=2", restoredSubset.operationCard("opt-1")!!.selectionWire())

    val empty = ChatThread(Clock.System)
    empty.showOperationCard(prReviewFix(recommendations = false))
    empty.chooseRecommendedOptions("opt-1")
    val emptyLine = empty.historyLines().single()
    assertEquals(emptyMap(), emptyLine.operationCard?.draftChoices)

    val restoredEmpty = ChatThread(Clock.System)
    restoredEmpty.replaceTranscript(listOf(restored(emptyLine)))
    restoredEmpty.showOperationCard(prReviewFix())
    assertNull(restoredEmpty.operationCard("opt-1")!!.selectionWire())
    assertEquals(
      "Choose an option for at least one thread to confirm.",
      restoredEmpty.operationCard("opt-1")!!.missingChoiceText(),
    )
  }

  @Test
  fun `coordinator submits only the chosen subset`() = runTest {
    val controls = FakeWorkControlRepository().apply {
      operationCards["opl-1"] = prReviewFix()
      newTaskOutcome = NewTaskStartOutcome.Started("SKILL-1", NewTaskKind.PrReviewFix, launchId = "opl-1")
    }
    val request = NewTaskStart(
      "/repo",
      "",
      null,
      "agent",
      NewTaskKind.PrReviewFix,
      null,
      null,
    )
    val coordinator = OperationCardCoordinator(controls, backgroundScope, { request })
    val thread = ChatThread(Clock.System)
    thread.showOperationCard(prReviewFix())
    thread.chooseOperationOption("opt-1", "T1", "2")

    coordinator.actions(thread).onConfirm("opt-1")
    runCurrent()

    assertEquals("T1=2", controls.newTaskRequests.single().confirmation?.selection)
  }

  @Test
  fun `stale confirmation stays inert while dismissal reaches the runtime`() = runTest {
    val stale = verify(canConfirm = false)
    val controls = FakeWorkControlRepository().apply { operationCards[stale.launchId] = stale }
    val request = NewTaskStart("/repo", "verify", null, "agent", NewTaskKind.Verify, null, null)
    val coordinator = OperationCardCoordinator(controls, backgroundScope, { request })
    val thread = ChatThread(Clock.System)
    thread.showOperationCard(stale)

    coordinator.actions(thread).onConfirm(stale.token)
    coordinator.actions(thread).onDismiss(stale.token)
    runCurrent()

    assertEquals(emptyList(), controls.newTaskRequests)
    assertEquals(listOf(stale.token), controls.dismissedCards)
  }

  @Test
  fun `stale adjustment uses the remembered request without its confirmation token`() = runTest {
    val stale = prReviewFix().copy(canConfirm = false, disabledReason = "The source anchors changed.")
    val controls = FakeWorkControlRepository().apply { operationCards[stale.launchId] = stale }
    val request = NewTaskStart(
      "/repo",
      "replace this proposal",
      null,
      "agent",
      NewTaskKind.PrReviewFix,
      null,
      null,
      target = "SKILL-453",
      branch = "feature/skill-453",
      confirmation = OperationCardConfirmation(stale.token),
    )
    val coordinator = OperationCardCoordinator(controls, backgroundScope, { null })
    coordinator.remember(stale.launchId, request)
    val thread = ChatThread(Clock.System)
    thread.showOperationCard(stale)

    coordinator.actions(thread).onAdjust(stale.token)
    runCurrent()

    val sent = controls.newTaskRequests.single()
    assertNull(sent.confirmation)
    assertEquals("SKILL-453", sent.target)
    assertEquals("feature/skill-453", sent.branch)
  }

  @Test
  fun `a restored stale card becomes dismissible after successful revalidation`() = runTest {
    val stale = prReviewFix().copy(canConfirm = false, disabledReason = "The source anchors changed.")
    val controls = FakeWorkControlRepository().apply {
      operationCards[stale.launchId] = stale
      dismissOutcome = OperationCardDismissOutcome.Dismissed
    }
    val coordinator = OperationCardCoordinator(controls, backgroundScope, { null })
    val thread = ChatThread(Clock.System)
    val saved = ChatThread(Clock.System).apply { showOperationCard(stale) }.historyLines().single()
    thread.replaceTranscript(listOf(restored(saved)))

    coordinator.revalidate(thread, listOf(stale.token))
    runCurrent()

    assertEquals(false, thread.operationCard(stale.token)!!.restoredCopy)
    coordinator.actions(thread).onDismiss(stale.token)
    runCurrent()
    assertEquals(listOf(stale.token), controls.dismissedCards)
  }

  @Test
  fun `stale proposal cannot mutate review choices`() {
    val thread = ChatThread(Clock.System)
    thread.showOperationCard(prReviewFix().copy(canConfirm = false, disabledReason = "The source anchors changed."))

    thread.chooseOperationOption("opt-1", "T1", "2")
    thread.chooseRecommendedOptions("opt-1")

    assertEquals(emptyMap(), thread.operationCard("opt-1")!!.choices)
    assertEquals(false, thread.operationCard("opt-1")!!.choicesSaved)
  }

  @Test
  fun `a double click claims one decision and a settled card stays inert`() {
    val thread = ChatThread(Clock.System)
    thread.showOperationCard(prReviewFix())

    assertNotNull(thread.beginOperationDecision("opt-1", OperationCardDecision.Confirm))
    assertNull(thread.beginOperationDecision("opt-1", OperationCardDecision.Dismiss))

    thread.showOperationCard(prReviewFix(OperationCardState.Completed))
    thread.showOperationCard(prReviewFix(OperationCardState.AwaitingConfirmation))

    assertEquals(OperationCardState.Completed, thread.operationCard("opt-1")!!.card.state)
    assertNull(thread.beginOperationDecision("opt-1", OperationCardDecision.Confirm))
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
