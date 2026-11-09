package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatBaselineCapture
import dev.skillbill.desktop.core.domain.model.ChatChangedPaths
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.testing.FakeChatChangesRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class NewTaskChatChangesTest {
  private val baseline = snapshot("baseline")
  private val secondTurn = snapshot("second-turn")

  @Test
  fun `only paths changed during the chat's own turns are attributed`() = runTest {
    val repository = FakeChatChangesRepository(captureResult = ChatBaselineCapture.Captured(baseline))
    val changes = tracker(repository, this, initialBaseline = null, initialPaths = null)

    changes.beginTurn(started = false)
    repository.changedPathsResult = ChatChangedPaths.Listed(setOf("a.kt"))
    changes.endTurn()
    repository.captureResult = ChatBaselineCapture.Captured(secondTurn)
    changes.beginTurn(started = true)
    repository.changedPathsResult = ChatChangedPaths.Listed(setOf("b.kt"))
    changes.endTurn()
    changes.refresh()
    testScheduler.advanceUntilIdle()

    assertEquals(listOf(baseline, secondTurn), repository.changedPathsCalls)
    assertEquals(baseline, changes.baseline)
    assertEquals(setOf("a.kt", "b.kt"), changes.attributedPaths)
    assertEquals(listOf<Set<String>?>(setOf("a.kt", "b.kt")), repository.readPaths)
  }

  @Test
  fun `a turn whose snapshot failed is not retried on every event and attributes nothing`() = runTest {
    val repository = FakeChatChangesRepository(captureResult = ChatBaselineCapture.Unavailable("git failed"))
    val changes = tracker(repository, this, initialBaseline = baseline, initialPaths = emptySet())

    repeat(3) { changes.beginTurn(started = true) }
    changes.endTurn()

    assertEquals(listOf("/work/app"), repository.captureCalls)
    assertEquals(emptyList(), repository.changedPathsCalls)
    assertEquals(emptySet(), changes.attributedPaths)
  }

  @Test
  fun `a chat saved before attribution keeps reading every change`() = runTest {
    val repository = FakeChatChangesRepository(captureResult = ChatBaselineCapture.Captured(secondTurn))
    val changes = tracker(repository, this, initialBaseline = baseline, initialPaths = null)

    changes.beginTurn(started = true)
    changes.endTurn()
    testScheduler.advanceUntilIdle()

    assertEquals(emptyList(), repository.captureCalls)
    assertEquals(null, changes.attributedPaths)
    assertEquals(listOf<Set<String>?>(null), repository.readPaths)
  }

  private fun tracker(repository: FakeChatChangesRepository, scope: TestScope, initialBaseline: ChatChangesBaseline?, initialPaths: Set<String>?) =
    NewTaskChatChanges(
      repository, scope, StandardTestDispatcher(scope.testScheduler), "/work/app", initialBaseline,
      resumed = initialBaseline != null, repositoryMissing = false, captureAttempted = initialBaseline != null, initialAttributedPaths = initialPaths,
    )

  private fun snapshot(sha: String) = ChatChangesBaseline(sha, emptyList(), null, Instant.fromEpochMilliseconds(1))
}
