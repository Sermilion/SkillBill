package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.domain.model.ChatBaselineCapture
import dev.skillbill.desktop.core.domain.model.ChatChangedPaths
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatChangesRead
import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.create
import skillbill.model.EnvironmentContext
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

  private fun snapshot(): ChatChangesBaseline = assertIs<ChatBaselineCapture.Captured>(reads.capture(repo.path)).baseline

  private fun committed(vararg names: String) {
    git("init", "-q", "-b", "main")
    git("config", "user.email", "test@example.com")
    git("config", "user.name", "Test")
    names.forEach { write(it, "initial\n") }
    git("add", ".")
    git("commit", "-q", "-m", "initial")
  }

  private fun write(name: String, text: String) = File(repo, name).writeText(text)

  private fun git(vararg args: String) {
    val process = ProcessBuilder(listOf("git", *args)).directory(repo).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { output }
  }
}
