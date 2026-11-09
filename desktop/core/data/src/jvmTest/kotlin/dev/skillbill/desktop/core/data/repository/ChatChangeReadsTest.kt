package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.domain.model.ChatBaselineCapture
import dev.skillbill.desktop.core.domain.model.ChatChangedPaths
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatChangesRead
import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.chatScopeTarget
import dev.skillbill.desktop.core.data.runtime.toPort
import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.create
import skillbill.model.EnvironmentContext
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import skillbill.ports.workchanges.model.WorkChangeListResult
import skillbill.ports.workchanges.model.WorkFileDiffResult
import kotlin.time.Clock

class ChatChangeReadsTest {
  private val home = Files.createTempDirectory("chat-change-reads")
  private val repo = home.resolve("repo").toFile().apply { mkdirs() }
  private val runtime = RuntimeComponent::class.create(
    RuntimeContext(
      EnvironmentContext(home.resolve("work.db").toString(), environment = emptyMap(), userHome = home, repositoryRoot = home),
      TransportContext(), WorkflowOpsContext(), OptionalCallbacks(),
    ),
  )
  private val reads = ChatChangeReads(runtime.workTreeSnapshotPort, runtime.workChangesPort, Clock.System)

  @Test
  fun `a turn's changed paths leave out edits made before its snapshot`() {
    committed("a.kt", "b.kt")
    write("a.kt", "edited before the turn\n")
    val turnStart = snapshot()

    write("b.kt", "edited by the turn\n")
    write("c.kt", "created by the turn\n")
    git("add", "c.kt")
    git("commit", "-q", "-m", "turn commit")

    assertEquals(ChatChangedPaths.Listed(setOf("b.kt", "c.kt")), reads.changedPaths(repo.path, turnStart))
  }

  @Test
  fun `a read lists only the attributed files and counts only their lines`() {
    committed("a.kt", "b.kt", "d.kt")
    val baseline = snapshot()
    write("b.kt", "one\ntwo\n")
    write("d.kt", "another chat\nwrote\nthis\n")

    val attributed = assertIs<ChatChangesRead.Listed>(reads.read(repo.path, baseline, setOf("b.kt"))).changes
    val everything = assertIs<ChatChangesRead.Listed>(reads.read(repo.path, baseline, null)).changes

    assertEquals(listOf("b.kt"), attributed.files.map { it.path })
    assertEquals(2, attributed.insertions)
    assertEquals(listOf("b.kt", "d.kt"), everything.files.map { it.path })
  }

  @AfterTest
  fun cleanUp() {
    home.toFile().deleteRecursively()
  }

  @Test
  fun `fast forwarding main during a turn does not attribute upstream changes to the chat`() {
    committed("a.kt", "b.kt")
    remoteAtHead()
    val baseline = snapshot()
    git("checkout", "-q", "-b", "incoming")
    write("a.kt", "upstream edit\n")
    write("upstream.kt", "upstream file\n")
    git("add", ".")
    git("commit", "-q", "-m", "upstream")
    remoteAtHead()
    git("checkout", "-q", "main")
    git("merge", "--ff-only", "incoming")

    assertEquals(ChatChangedPaths.Listed(emptySet()), reads.changedPaths(repo.path, baseline))
    val result = assertIs<ChatChangesRead.Listed>(reads.read(repo.path, baseline, null)).changes
    assertTrue(result.files.isEmpty())
    assertEquals(0, result.insertions)
    assertEquals(0, result.deletions)
    assertEquals(0, result.commitCount)
    assertFalse(result.uncommitted)
  }

  @Test
  fun `pull and chat edits in the same file keep only chat lines in counts and the changes window`() {
    committed("a.kt", "b.kt")
    write("a.kt", (1..20).joinToString("") { "line $it\n" })
    git("add", ".")
    git("commit", "-q", "-m", "long file")
    remoteAtHead()
    write("b.kt", "dirty before chat\n")
    val baseline = snapshot()
    git("stash", "push", "-q")
    git("checkout", "-q", "-b", "incoming")
    write("a.kt", (1..20).joinToString("") { if (it == 2) "upstream line\n" else "line $it\n" })
    git("add", ".")
    git("commit", "-q", "-m", "upstream")
    remoteAtHead()
    git("checkout", "-q", "main")
    git("merge", "--ff-only", "incoming")
    git("stash", "pop", "-q")
    write("a.kt", File(repo, "a.kt").readText().replace("line 18\n", "chat line\n"))
    git("add", "a.kt")
    git("commit", "-q", "-m", "chat edit")
    write("a.kt", File(repo, "a.kt").readText() + "uncommitted chat line\n")

    assertEquals(ChatChangedPaths.Listed(setOf("a.kt")), reads.changedPaths(repo.path, baseline))
    val result = assertIs<ChatChangesRead.Listed>(reads.read(repo.path, baseline, setOf("a.kt"))).changes
    assertEquals(listOf("a.kt"), result.files.map { it.path })
    assertEquals(2, result.insertions)
    assertEquals(1, result.deletions)
    assertEquals(1, result.commitCount)
    assertEquals(listOf("chat edit"), result.timeline.commits.map { it.subject })
    assertTrue(result.uncommitted)
    val target = chatScopeTarget("session", repo.path, baseline, result.timeline, setOf("a.kt"), result.comparisonBaseSha)
    val comparison = assertIs<ChangeBaseline.RunStart>(target.allBaseline)
    val listed = assertIs<WorkChangeListResult.Listed>(runtime.workChangesPort.changes(comparison.toPort()))
    assertEquals(result.insertions, listed.insertions)
    assertEquals(result.deletions, listed.deletions)
    val patch = assertIs<WorkFileDiffResult.Patch>(runtime.workChangesPort.fileDiff(repo.path, listed.baseSha, listed.files.single())).text
    assertTrue("+chat line" in patch)
    assertTrue("+uncommitted chat line" in patch)
    assertFalse("+upstream line" in patch)
    assertEquals("dirty before chat\n", File(repo, "b.kt").readText())
    assertEquals(" M a.kt\n M b.kt", gitOutput("status", "--short"))
  }

  @Test
  fun `an upstream conflict with the prechat snapshot reports unavailable without modifying files`() {
    committed("a.kt")
    remoteAtHead()
    write("a.kt", "prechat edit\n")
    val baseline = snapshot()
    git("checkout", "--", "a.kt")
    write("a.kt", "upstream edit\n")
    git("add", ".")
    git("commit", "-q", "-m", "upstream")
    remoteAtHead()

    assertIs<ChatChangedPaths.Unavailable>(reads.changedPaths(repo.path, baseline))
    assertIs<ChatChangesRead.Unavailable>(reads.read(repo.path, baseline, null))
    assertEquals("upstream edit\n", File(repo, "a.kt").readText())
    assertEquals("", gitOutput("status", "--porcelain"))
  }

  @Test
  fun `a project lists this chat's changes in both checkouts while preserving each dirty baseline`() {
    committed("a.kt", "b.kt")
    val sibling = home.resolve("linked").toFile()
    git("worktree", "add", "-q", "-b", "linked", sibling.path)
    File(sibling, "a.kt").writeText("pre-existing dirty edit\n")
    // Changed paths of a linked checkout come from git, which uses forward slashes on Windows too;
    // worktree roots keep the native form.
    val siblingGitRoot = sibling.path.replace('\\', '/')
    val baseline = snapshot()
    write("b.kt", "chat in original\n")
    File(sibling, "b.kt").writeText("chat in linked\nextra line\n")

    val changed = assertIs<ChatChangedPaths.Listed>(reads.changedPaths(repo.path, baseline))
    assertEquals(setOf("b.kt", "$siblingGitRoot/b.kt"), changed.paths)
    val result = assertIs<ChatChangesRead.Listed>(reads.read(repo.path, baseline, changed.paths)).changes
    assertEquals(2, result.files.size)
    assertEquals(3, result.insertions)
    assertEquals(2, result.deletions)
    assertEquals(setOf(repo.path, sibling.path), result.worktrees.map { it.repositoryRoot }.toSet())
    val linked = result.worktrees.single { it.repositoryRoot == sibling.path }
    val target = chatScopeTarget("chat", linked.repositoryRoot, linked.baseline, linked.changes.timeline, linked.paths, linked.changes.comparisonBaseSha)
    assertEquals(sibling.path, target.worktreeRoot)
    assertEquals(setOf("b.kt"), target.paths)
    val comparison = assertIs<ChangeBaseline.RunStart>(target.allBaseline)
    val files = assertIs<WorkChangeListResult.Listed>(runtime.workChangesPort.changes(comparison.toPort()))
    assertEquals(listOf("b.kt"), files.files.map { it.path })
    assertEquals("pre-existing dirty edit\n", File(sibling, "a.kt").readText())
  }

  private fun remoteAtHead() {
    git("update-ref", "refs/remotes/origin/main", "HEAD")
    git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")
  }

  private fun snapshot(): ChatChangesBaseline = assertIs<ChatBaselineCapture.Captured>(reads.capture(repo.path)).baseline

  private fun committed(vararg names: String) {
    git("init", "-q", "-b", "main")
    git("config", "user.email", "test@example.com")
    git("config", "user.name", "Test")
    // Windows runners enable autocrlf system-wide; the fixtures compare exact LF bytes.
    git("config", "core.autocrlf", "false")
    names.forEach { write(it, "initial\n") }
    git("add", ".")
    git("commit", "-q", "-m", "initial")
  }

  private fun write(name: String, text: String) = File(repo, name).writeText(text)

  private fun git(vararg args: String) { gitOutput(*args) }

  private fun gitOutput(vararg args: String): String {
    val process = ProcessBuilder(listOf("git", *args)).directory(repo).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { output }
    return output.trimEnd()
  }
}
