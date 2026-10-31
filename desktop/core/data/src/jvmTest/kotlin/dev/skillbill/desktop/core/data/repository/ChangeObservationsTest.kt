package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.ChangesState
import dev.skillbill.desktop.core.domain.model.OpenOutcome
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.create
import skillbill.model.EnvironmentContext
import skillbill.ports.editor.FileEditorLauncherPort
import skillbill.ports.idestatus.model.WorkChangeBaseline
import skillbill.ports.workchanges.WorkChangesPort
import skillbill.ports.workchanges.model.WorkChangeListResult
import skillbill.ports.workchanges.model.WorkChangeStatus
import skillbill.ports.workchanges.model.WorkChangedFile
import skillbill.ports.workchanges.model.WorkCommitRange
import skillbill.ports.workchanges.model.WorkFileDiffResult
import skillbill.ports.workchanges.model.WorkOutsideCommitsResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ChangeObservationsTest {
  private val root = Files.createTempDirectory("change-observations")
  private val runtime = RuntimeComponent::class.create(
    RuntimeContext(
      EnvironmentContext(root.resolve("work.db").toString(), environment = emptyMap(), userHome = root, repositoryRoot = root),
      TransportContext(),
      WorkflowOpsContext(),
      OptionalCallbacks(),
    ),
  )
  private val baseline = ChangeBaseline.SubtaskStart(root.toString(), "abc", emptyList())

  @Test
  fun `polls while running, reads once when idle and never without a baseline`() = runTest {
    val port = FakePort(files = 2)
    val changes = observations(port, launcher = RecordingLauncher(true))
    val running = mutableListOf<ChangesState>()
    val job = launch { changes.observe(baseline, running = true, Mutex()).collect { running += it } }
    runCurrent()
    assertEquals(listOf(ChangesState.Loading::class, ChangesState.Listed::class), running.map { it::class })
    assertEquals(2, (running.last() as ChangesState.Listed).changes.files.size)

    port.files = 3
    advanceTimeBy(CHANGE_REFRESH_INTERVAL)
    runCurrent()
    assertEquals(3, (running.last() as ChangesState.Listed).changes.files.size)
    job.cancel()

    port.calls = 0
    val idle = launch { changes.observe(baseline, running = false, Mutex()).collect { } }
    advanceTimeBy(30.seconds)
    runCurrent()
    assertTrue(idle.isCompleted)
    assertEquals(1, port.calls)

    port.calls = 0
    assertEquals(listOf<ChangesState>(ChangesState.NoSubtask), changes.observe(null, running = true, Mutex()).toList())
    assertEquals(0, port.calls)
  }

  @Test
  fun `opens a changed file under the worktree with editors stripped and never launches a deleted file`() = runTest {
    val launcher = RecordingLauncher(true)
    val changes = observations(FakePort(files = 0), launcher)

    val modified = ChangedFile("src/A.kt", null, ChangeStatus.Modified, 1, 1)
    assertEquals(OpenOutcome.Opened, changes.open(root.toString(), modified))
    assertEquals(listOf(root.resolve("src/A.kt") to mapOf("PATH" to "p")), launcher.launches)

    val deleted = ChangedFile("src/B.kt", null, ChangeStatus.Deleted, 0, 3)
    assertEquals(OpenOutcome.NotOpened, changes.open(root.toString(), deleted))
    assertEquals(1, launcher.launches.size)
  }

  private fun TestScope.observations(port: WorkChangesPort, launcher: FileEditorLauncherPort) = ChangeObservations(
    runtime,
    NoopDiagnostics,
    mapOf("VISUAL" to "v", "EDITOR" to "e", "PATH" to "p"),
    port,
    launcher,
    StandardTestDispatcher(testScheduler),
  )

  private class FakePort(var files: Int) : WorkChangesPort {
    var calls = 0

    override fun changes(baseline: WorkChangeBaseline): WorkChangeListResult {
      calls++
      val listed = List(files) { WorkChangedFile("f$it.kt", null, WorkChangeStatus.MODIFIED, 1, 0) }
      return WorkChangeListResult.Listed("abc", listed, files, 0)
    }

    override fun fileDiff(worktreeRoot: String, baseSha: String, file: WorkChangedFile): WorkFileDiffResult = WorkFileDiffResult.Binary

    override fun committedChanges(worktreeRoot: String, fromSha: String, toSha: String): WorkChangeListResult =
      WorkChangeListResult.Failed("unused")

    override fun committedFileDiff(
      worktreeRoot: String,
      fromSha: String,
      toSha: String,
      file: WorkChangedFile,
    ): WorkFileDiffResult = WorkFileDiffResult.Binary

    override fun uncommittedChanges(worktreeRoot: String): WorkChangeListResult = WorkChangeListResult.Failed("unused")

    override fun outsideCommits(
      worktreeRoot: String,
      targetBranch: String?,
      subtaskRanges: List<WorkCommitRange>,
    ): WorkOutsideCommitsResult = WorkOutsideCommitsResult.Failed("unused")
  }

  private class RecordingLauncher(private val result: Boolean) : FileEditorLauncherPort {
    val launches = mutableListOf<Pair<Path, Map<String, String>>>()

    override fun open(path: Path, environment: Map<String, String>): Boolean {
      launches += path to environment
      return result
    }
  }

  private object NoopDiagnostics : Diagnostics {
    override fun record(category: String) = Unit

    override fun recordRequest(category: String, fields: Map<String, Number>) = Unit
  }
}
