package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatChanges
import dev.skillbill.desktop.core.domain.model.ChatChangesRead
import dev.skillbill.desktop.core.domain.model.ChangeScopeTimeline
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.domain.repository.ChatChangesRepository
import dev.skillbill.desktop.core.domain.model.ChatBaselineCapture
import dev.skillbill.desktop.core.domain.model.ChatChangedPaths
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatProjectWorktrees
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.testing.FakeChatChangesRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.time.Instant

class NewTaskChatChangesTest {
  private val baseline = snapshot("baseline")
  private val secondTurn = snapshot("second-turn")

  @Test
  fun `opening and reopening show pre-existing edits without resetting the starting HEAD`() = runTest {
    val original = baseline.copy(untrackedPaths = listOf("untracked.kt"), stashSha = "dirty-snapshot")
    val repository = FakeChatChangesRepository(captureResult = ChatBaselineCapture.Captured(original), readResult = listedChanges(7))
    val fresh = tracker(repository, this, null, null)
    val resumed = tracker(repository, this, original, emptySet())
    testScheduler.advanceUntilIdle()

    for (changes in listOf(fresh, resumed)) {
      val shown = assertIs<ChatChangesState.Shown>(changes.state.value)
      assertEquals(7, shown.changes.insertions)
      assertEquals(listOf("a.kt"), shown.changes.files.map { it.path })
      assertEquals(null, shown.paths)
      assertEquals(emptyList(), shown.baseline.untrackedPaths)
      changes.end()
    }
    assertEquals(listOf("/work/app"), repository.captureCalls)
    assertTrue(repository.openingReadCalls.all { (_, start) -> start.headSha == original.headSha && start.stashSha == null })
  }

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
  }

  @Test
  fun `a turn associates a referenced sibling checkout and excludes a concurrent unused checkout`() = runTest {
    val project = baseline.copy(projectId = "project", worktrees = mapOf("/work/app" to baseline, "/work/app-main" to baseline, "/work/other-run" to baseline))
    val repository = FakeChatChangesRepository(captureResult = ChatBaselineCapture.Captured(project))
    repository.projectResult = ChatProjectWorktrees.Listed("project", project.worktrees.keys.toList())
    val changes = tracker(repository, this, null, null)

    changes.beginTurn(false)
    changes.recordTool(ChatToolCall("edit", "shell", "write file.kt", null, "/work/app-main"))
    repository.changedPathsResult = ChatChangedPaths.Listed(setOf("/work/app-main/file.kt"))
    changes.endTurn()

    assertEquals(setOf("/work/app", "/work/app-main"), repository.changedPathsCalls.single().worktrees.keys)
    assertEquals(setOf("/work/app-main/file.kt"), changes.attributedPaths)
    assertEquals(project, changes.baseline)
  }

  @Test
  fun `a previous single checkout baseline gains project associations without replacing its original snapshot`() = runTest {
    val captured = secondTurn.copy(projectId = "project", worktrees = mapOf("/work/app" to secondTurn, "/work/main" to secondTurn))
    val repository = FakeChatChangesRepository(captureResult = ChatBaselineCapture.Captured(captured))
    val changes = tracker(repository, this, baseline, setOf("existing.kt"))

    changes.beginTurn(true)
    changes.endTurn()
    val migrated = changes.baseline!!
    assertEquals(baseline.headSha, migrated.headSha)
    assertEquals(baseline, migrated.worktrees["/work/app"])
    assertEquals(secondTurn, migrated.worktrees["/work/main"])
    assertEquals(setOf("existing.kt"), changes.attributedPaths)
    repository.captureResult = ChatBaselineCapture.Captured(captured)
    changes.beginTurn(true)
    changes.endTurn()
    assertEquals(migrated, changes.baseline)
  }

  @Test
  fun `a worktree created during a turn is associated before its first relative path tool use`() = runTest {
    val initial = baseline.copy(projectId = "project", worktrees = mapOf("/work/app" to baseline))
    val repository = FakeChatChangesRepository(captureResult = ChatBaselineCapture.Captured(initial))
    val changes = tracker(repository, this, null, null)
    changes.beginTurn(false)
    val discovered = secondTurn.copy(projectId = "project", worktrees = mapOf("/work/app" to secondTurn, "/work/new" to secondTurn))
    repository.captureResult = ChatBaselineCapture.Captured(discovered)
    repository.projectResult = ChatProjectWorktrees.Listed("project", listOf("/work/app", "/work/new"))

    changes.recordTool(ChatToolCall("edit", "shell", "cd ../new && edit file.kt", null))
    repository.changedPathsResult = ChatChangedPaths.Listed(setOf("/work/new/file.kt"))
    changes.endTurn()

    assertEquals(listOf("/work/app", "/work/new"), repository.captureCalls)
    assertEquals(secondTurn, changes.baseline!!.worktrees["/work/new"])
    assertEquals(baseline, changes.baseline!!.worktrees["/work/app"])
    assertEquals(secondTurn, repository.changedPathsCalls.single().worktrees["/work/new"])
    assertEquals(setOf("/work/new/file.kt"), changes.attributedPaths)
  }

  @Test
  fun `visible chat discovers edits during a running command and refreshes attributed files while idle`() = runTest {
    val repository = FakeChatChangesRepository(captureResult = ChatBaselineCapture.Captured(baseline))
    val changes = tracker(repository, this, null, null)
    changes.beginTurn(false)
    backgroundScope.launch { changes.observe() }
    runCurrent()
    repository.changedPathsResult = ChatChangedPaths.Listed(setOf("a.kt"))
    repository.readResult = listedChanges(1)

    advanceTimeBy(1_000)
    runCurrent()

    assertEquals(setOf("a.kt"), changes.attributedPaths)
    assertEquals(1, assertIs<ChatChangesState.Shown>(changes.state.value).changes.insertions)
    changes.endTurn()
    repository.changedPathsResult = ChatChangedPaths.Listed(setOf("unrelated.kt"))
    repository.readResult = listedChanges(4)
    advanceTimeBy(1_000)
    runCurrent()
    assertEquals(setOf("a.kt"), changes.attributedPaths)
    assertEquals(4, assertIs<ChatChangesState.Shown>(changes.state.value).changes.insertions)
    changes.end()
    repository.readResult = listedChanges(9)
    advanceTimeBy(1_000)
    runCurrent()
    assertEquals(4, assertIs<ChatChangesState.Shown>(changes.state.value).changes.insertions)
  }

  @Test
  fun `refresh requests let a slow git read finish and then publish the latest changes`() = runTest {
    val readStarted = CompletableDeferred<Unit>()
    val readFinished = CompletableDeferred<Unit>()
    val releaseRead = CompletableDeferred<Unit>()
    val fake = FakeChatChangesRepository(captureResult = ChatBaselineCapture.Captured(baseline))
    val repository = object : ChatChangesRepository by fake {
      override suspend fun readSinceOpening(repositoryRoot: String, baseline: ChatChangesBaseline): ChatChangesRead {
        val result = fake.readResult
        if (!readStarted.isCompleted) {
          readStarted.complete(Unit)
          releaseRead.await()
          readFinished.complete(Unit)
        }
        return result
      }
    }
    val changes = tracker(repository, this, null, null)
    changes.beginTurn(false)
    fake.changedPathsResult = ChatChangedPaths.Listed(setOf("a.kt"))
    fake.readResult = listedChanges(1)
    changes.refresh()
    readStarted.await()
    fake.readResult = listedChanges(5)
    changes.refresh()
    releaseRead.complete(Unit)
    testScheduler.advanceUntilIdle()

    assertTrue(readFinished.isCompleted)
    assertEquals(5, assertIs<ChatChangesState.Shown>(changes.state.value).changes.insertions)
    fake.readResult = ChatChangesRead.Unavailable("git temporarily unavailable")
    changes.refresh()
    testScheduler.advanceUntilIdle()
    assertEquals(5, assertIs<ChatChangesState.Shown>(changes.state.value).changes.insertions)
  }

  private fun listedChanges(insertions: Int) = ChatChangesRead.Listed(
    ChatChanges(listOf(ChangedFile("a.kt", null, ChangeStatus.Modified, insertions, 0)), insertions, 0, 0, true,
      "baseline", ChangeScopeTimeline("baseline", "baseline", emptyList())),
  )

  private fun tracker(repository: ChatChangesRepository, scope: TestScope, initialBaseline: ChatChangesBaseline?, initialPaths: Set<String>?) =
    NewTaskChatChanges(
      repository, scope, StandardTestDispatcher(scope.testScheduler), "/work/app", initialBaseline,
      resumed = initialBaseline != null, repositoryMissing = false, captureAttempted = initialBaseline != null, initialAttributedPaths = initialPaths,
    )

  private fun snapshot(sha: String) = ChatChangesBaseline(sha, emptyList(), null, Instant.fromEpochMilliseconds(1))
}
