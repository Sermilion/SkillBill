package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ApprovalCard
import dev.skillbill.desktop.core.domain.model.ApprovalCardKey
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.domain.model.ChatSteeringResult
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class ChatThreadTest {
  @Test
  fun `claimed originals survive completion drain and fallback restores their metadata before later entries`() {
    val thread = ChatThread(Clock.System)
    val image = ChatImage(byteArrayOf(7), "image/png")
    var rejected = false
    val first = QueuedChatMessage("first", pauseAndAct = true, images = listOf(image), action = null, onRejected = { rejected = true })
    val later = QueuedChatMessage("later", pauseAndAct = false, action = null)
    thread.enqueue(first)
    val claim = thread.claimDelivery("one")!!
    thread.enqueue(later)
    assertNull(thread.claimDelivery("two"))
    assertNull(thread.takeQueuedMessage())
    thread.reduceDelivery(ChatSteeringResult.Rejected("one", "not sent"))
    val restored = thread.takeQueuedEntries()
    assertTrue(restored.first() === first)
    assertTrue(restored.first().images.single() === image)
    assertEquals(listOf(first, later), restored)
    restored.first().onRejected!!.invoke()
    assertTrue(rejected)
    assertEquals(listOf(first), claim.entries)

    thread.enqueue(first)
    thread.claimDelivery("uncertain")
    thread.enqueue(later)
    thread.reduceDelivery(ChatSteeringResult.DeliveryUncertain("uncertain", "unknown"))
    val rejection = thread.reduceDelivery(ChatSteeringResult.Rejected("uncertain", "late failure"))
    assertEquals(ChatThread.DeliveryTransition.Restored("late failure"), rejection)
    assertFalse(thread.hasDeliveryClaim())
    assertNull(thread.delivery.value)
    assertEquals(listOf(first, later), thread.takeQueuedEntries())
    assertEquals(ChatThread.DeliveryTransition.Ignored, thread.reduceDelivery(ChatSteeringResult.Rejected("uncertain", "duplicate")))
  }

  @Test
  fun `unavailable transport result does not restore an uncertain batch`() {
    val thread = ChatThread(Clock.System)
    val original = QueuedChatMessage("first", null, false)
    val later = QueuedChatMessage("later", null, false)
    thread.enqueue(original)
    thread.claimDelivery("uncertain")
    thread.enqueue(later)
    thread.reduceDelivery(ChatSteeringResult.DeliveryUncertain("uncertain", "unknown"))

    assertEquals(ChatThread.DeliveryTransition.Ignored,
      thread.reduceDelivery(ChatSteeringResult.NoActiveTurn("uncertain", "transport unavailable")))
    assertTrue(thread.hasDeliveryClaim())
    assertEquals(listOf(later), thread.queued.value)
  }

  @Test
  fun `agent questions and tool calls never create permission cards without a harness request`() {
    val thread = ChatThread(Clock.System)
    val agent = ChatAgent("codex", null, null)
    val question = "May I edit this file?\nCodex permission request\nAllow once\nAlways allow"
    thread.show(agent, ChatEvent.TextDelta(question))
    thread.show(agent, ChatEvent.ToolUse(ChatToolCall("edit-1", "apply_patch", "{}", "Edit the requested file")))
    thread.show(agent, ChatEvent.Completed)

    assertEquals(emptyList(), thread.transcript.value.filterIsInstance<ChatMessage.Approval>())
    assertEquals(question, thread.transcript.value.filterIsInstance<ChatMessage.Agent>().single().text)

    val card = approvalCard()
    thread.show(agent, ChatEvent.ApprovalRequested(card))

    assertEquals(listOf(card), thread.transcript.value.filterIsInstance<ChatMessage.Approval>().map { it.card })
  }

  @Test
  fun `tool-only messages share one tool group until a message with text closes the entry`() {
    val thread = ChatThread(Clock.System)
    val calls = (1..3).map { ChatToolCall("t$it", "Bash", "{}", null) }
    calls.forEach { call ->
      thread.show(null, ChatEvent.MessageStarted)
      thread.show(null, ChatEvent.ToolUse(call))
      thread.show(null, ChatEvent.ToolResult(call.id, "ok", false))
    }
    listOf(ChatEvent.MessageStarted, ChatEvent.TextDelta("Done."), ChatEvent.Completed).forEach { thread.show(null, it) }
    val entry = thread.transcript.value.filterIsInstance<ChatMessage.Agent>().single()
    assertEquals(listOf("t1", "t2", "t3"), entry.tools.map { it.call.id })
    assertEquals("Done.", entry.text)
  }

  @Test
  fun `unsupported Junie reason and historical prose restore without a runnable approval`() {
    val thread = ChatThread(Clock.System)
    thread.append(ChatMessage.Agent("Earlier Junie prose", false, emptyList()))
    val reason = "Junie ACP permission-request coverage remains unverified. " + "Full explanation. ".repeat(1000)
    thread.show(ChatAgent("junie", null, null), ChatEvent.Failed(ChatFailure.ApprovalUnsupported, reason))
    val lines = thread.historyLines()
    assertEquals("Earlier Junie prose", lines[0].text)
    assertEquals(ChatHistoryRole.Error, lines[1].role)
    assertEquals(reason, lines[1].text)
    assertEquals(ChatMessage.Error(ChatFailure.ApprovalUnsupported, reason), restored(lines[1]))
  }

  @Test
  fun `stream chunks join within a message and separate messages survive history restoration`() {
    val thread = ChatThread(Clock.System)
    val call = ChatToolCall("t1", "shell", "git status", null)
    listOf(
      ChatEvent.MessageStarted, ChatEvent.TextDelta("Check"), ChatEvent.TextDelta("ing."), ChatEvent.ToolUse(call),
      ChatEvent.MessageStarted, ChatEvent.TextDelta("Done."), ChatEvent.ToolResult("t1", "clean", false), ChatEvent.Completed,
    ).forEach { thread.show(null, it) }
    val messages = thread.transcript.value.filterIsInstance<ChatMessage.Agent>()
    assertEquals(listOf("Checking.", "Done."), messages.map { it.text })
    assertEquals(listOf(false, false), messages.map { it.streaming })
    assertEquals("clean", messages.first().tools.single().result?.output)
    assertEquals(listOf("Checking.", "Done."), thread.historyLines().map(::restored).filterIsInstance<ChatMessage.Agent>().map { it.text })
  }

  @Test
  fun `history keeps action-only replies and selected operator actions for restoration`() {
    val suggested = listOf(ChatSuggestedAction("Review", "Review this", ChatPermissionMode.ReadOnly, NewTaskActionKind.Review))
    val selected = ChatSuggestedAction("Inspect", "Inspect this", ChatPermissionMode.Act)
    val thread = ChatThread(Clock.System)
    thread.append(ChatMessage.Operator(selected.instruction, selected))
    thread.append(ChatMessage.Agent("", streaming = false, tools = emptyList(), actions = suggested))

    val lines = thread.historyLines()

    assertEquals(
      listOf(
        ChatHistoryLine(ChatHistoryRole.Operator, selected.instruction, selectedAction = selected),
        ChatHistoryLine(ChatHistoryRole.Agent, "", actions = suggested),
      ),
      lines,
    )
    assertEquals(selected, (restored(lines[0]) as ChatMessage.Operator).action)
    assertEquals(suggested, (restored(lines[1]) as ChatMessage.Agent).actions)
  }

  @Test
  fun `images stay in the session and a restored operator message has none`() {
    val thread = ChatThread(Clock.System)
    thread.append(ChatMessage.Operator("look", images = listOf(ChatImage(byteArrayOf(1), "image/png"))))

    assertEquals(listOf(ChatMessage.Operator("look", null)), thread.historyLines().map(::restored))
  }

  @Test
  fun `an image-only message is saved as its image labels and no image data`() {
    val thread = ChatThread(Clock.System)
    val image = ChatImage(byteArrayOf(1), "image/png")
    thread.append(ChatMessage.Operator("", images = listOf(image, image)))

    assertEquals(listOf(ChatMessage.Operator("[Image #1] [Image #2]", null)), thread.historyLines().map(::restored))
  }

  @Test
  fun `output after an approval card continues below it and its tool result reaches the call above`() {
    val thread = ChatThread(Clock.System)
    val agent = ChatAgent("claude", null, null)
    val call = ChatToolCall("bash-1", "Bash", "curl -sI https://example.com", "Fetch headers")
    val card = approvalCard()
    thread.show(agent, ChatEvent.TextDelta("Fetching."))
    thread.show(agent, ChatEvent.ToolUse(call))
    thread.show(agent, ChatEvent.ApprovalRequested(card))
    thread.show(agent, ChatEvent.ToolResult(call.id, "HTTP/2 200", false))
    thread.show(agent, ChatEvent.TextDelta("It returned 200."))

    val (before, shown, after) = thread.transcript.value
    before as ChatMessage.Agent
    assertEquals("Fetching.", before.text)
    assertEquals(false, before.streaming)
    assertEquals(ChatEvent.ToolResult(call.id, "HTTP/2 200", false), before.tools.single().result)
    assertEquals(card, (shown as ChatMessage.Approval).card)
    after as ChatMessage.Agent
    assertEquals("It returned 200.", after.text)
    assertEquals(true, after.streaming)
    assertEquals(3, thread.transcript.value.size)
  }

  @Test
  fun `a start confirmation notice restores as a confirmation`() {
    val text = "Started goal run APP-1. $START_AGAIN_SUFFIX"
    val notice = restored(ChatHistoryLine(ChatHistoryRole.Notice, text)) as ChatMessage.Notice
    assertEquals(text, notice.text)
    assertEquals(true, notice.confirmation)
    assertEquals(false, (restored(ChatHistoryLine(ChatHistoryRole.Notice, "Stopped")) as ChatMessage.Notice).confirmation)
  }

  private fun approvalCard() = ApprovalCard(
    key = ApprovalCardKey("approval-1", "repo", "run-1", "gen-1", "claude", "request-1", false),
    checkoutRoot = "/repo",
    providerSessionId = null,
    title = "Bash",
    reason = null,
    subject = "curl -sI https://example.com",
    inputText = "curl -sI https://example.com",
    options = emptyList(),
    actionFingerprint = null,
    authorityScope = null,
    createdAt = "2026-11-07T00:00:00Z",
    deadlineAt = "2026-11-07T00:10:00Z",
    state = "pending",
    decisionSource = null,
    selectedOptionId = null,
    ruleId = null,
    providerPayload = "{}",
  )
}
