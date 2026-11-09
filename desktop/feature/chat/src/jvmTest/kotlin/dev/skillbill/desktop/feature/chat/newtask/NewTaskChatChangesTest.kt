package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatBaselineCapture
import dev.skillbill.desktop.core.domain.model.ChatChangedPaths
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatProjectWorktrees
import dev.skillbill.desktop.core.domain.model.ChatToolCall
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

  private fun tracker(repository: FakeChatChangesRepository, scope: TestScope, initialBaseline: ChatChangesBaseline?, initialPaths: Set<String>?) =
    NewTaskChatChanges(
      repository, scope, StandardTestDispatcher(scope.testScheduler), "/work/app", initialBaseline,
      resumed = initialBaseline != null, repositoryMissing = false, captureAttempted = initialBaseline != null, initialAttributedPaths = initialPaths,
    )

  private fun snapshot(sha: String) = ChatChangesBaseline(sha, emptyList(), null, Instant.fromEpochMilliseconds(1))
}
