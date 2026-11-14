package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.ChatHistoryRole
import dev.skillbill.desktop.core.domain.model.ChatNewTaskContext
import dev.skillbill.desktop.core.domain.model.ChatSessionAcquisition
import dev.skillbill.desktop.core.testing.FakeExecutionChatRepository
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DevinChatContinuationTest {
  @Test
  fun `Devin continues its acquired session after pending turn and baseline precision change`() = runTest {
    val root = createTempDirectory("devin-continuation").toRealPath().toString()
    val clock = object : Clock {
      override fun now() = Instant.parse("2026-11-14T12:00:00.123456789Z")
    }
    val agent = ChatAgent("devin", "swe-2-high-lightning", null)
    val baseline = ChatChangesBaseline("head", emptyList(), null, clock.now())
    val stored = ChatHistoryEntry(
      "acquired", null, agent, clock.now(), "earlier question",
      listOf(ChatHistoryLine(ChatHistoryRole.Operator, "earlier question")),
      ChatNewTaskContext(root, "main", changesBaseline = baseline.copy(
        takenAt = Instant.fromEpochMilliseconds(baseline.takenAt.toEpochMilliseconds()),
      ), changesCaptureAttempted = true),
    )
    val repository = FakeExecutionChatRepository(supported = setOf("devin")).apply {
      acquisitionStates[stored.sessionId] = ChatSessionAcquisition.Acquired(stored)
      events = { flowOf(ChatEvent.TextDelta("continued"), ChatEvent.Completed) }
    }
    val chat = NewTaskChat(
      repository, backgroundScope, clock, stored.sessionId, 1L, root, "main", agent,
      initiallyStarted = true, dispatcher = StandardTestDispatcher(testScheduler),
      initialChangesBaseline = baseline, changesCaptureAttempted = true,
      diagnostics = RecordingDiagnostics(), initiallyAcquired = true,
    )

    chat.send("next question")
    runCurrent()

    val request = repository.requests.single()
    assertEquals(stored.sessionId, request.sessionId)
    assertEquals("next question", request.prompt)
    assertFalse(request.firstTurn)
    chat.end()
  }
}
