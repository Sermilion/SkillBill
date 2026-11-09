package skillbill.infrastructure.launcher.process.launch

import org.junit.jupiter.api.Test
import skillbill.infrastructure.launcher.process.interaction.AgentRunSessionCapture
import skillbill.infrastructure.launcher.process.interaction.AgentRunSessionOutcome
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProcessRunLifetimeTest {
  @Test
  fun `settled provider receives EOF and reaps its child before any forced termination`() {
    val process = ShutdownProcess(exitOnEof = true)
    val tree = AgentRunProcessTree(process)
    val owned = mutableSetOf(tree)
    val degradation = ProcessRunDegradationRecorder()

    val release = lifetime(tree, owned, degradation).release(settledWait())

    assertEquals(listOf("stdin_close"), process.actions)
    assertTrue(release.processTreeExited)
    assertTrue(owned.isEmpty())
    assertFalse(release.outputCaptureIncomplete)
    assertEquals("Completed work", release.stdoutCapture.text)
    assertTrue(degradation.snapshot().isEmpty())
  }

  @Test
  fun `provider that ignores EOF still has its owned process tree forcibly terminated`() {
    val process = ShutdownProcess(exitOnEof = false)
    val tree = AgentRunProcessTree(process)
    val owned = mutableSetOf(tree)
    val degradation = ProcessRunDegradationRecorder()
    val lifetime = lifetime(tree, owned, degradation)

    val release = lifetime.release(settledWait())

    assertEquals(listOf("stdin_close", "child_force", "root_force"), process.actions)
    assertTrue(release.processTreeExited)
    assertTrue(owned.isEmpty())
    assertSame(release, lifetime.release(settledWait()))
  }

  @Test
  fun `surviving child retains ownership and blocks continuation while completed prose is preserved`() {
    val process = ShutdownProcess(exitOnEof = false, childSurvives = true)
    val tree = AgentRunProcessTree(process)
    val owned = mutableSetOf(tree)
    val degradation = ProcessRunDegradationRecorder()
    val release = lifetime(tree, owned, degradation).release(settledWait())

    val settlement = settle(release, degradation.snapshot())

    assertFalse(release.processTreeExited)
    assertEquals(setOf(tree), owned)
    assertTrue(settlement.failed)
    assertTrue(settle(release, emptyList()).failed)
    assertContains(
      requireNotNull(sessionSettlement(null, null, false, release, emptyList()).cleanupFailure),
      "Owned process exit is unconfirmed",
    )
    assertSame(completed, settlement.outcome)
    assertEquals("Completed work", settlement.capture?.text)
    assertEquals("Completed work", release.stdoutCapture.text)
    assertContains(requireNotNull(settlement.failure), "Provider turn completed; launcher cleanup failed.")
    assertContains(requireNotNull(settlement.failure), "Owned process exit is unconfirmed")
    assertContains(requireNotNull(settlement.failure), "process_destroy_wait")
    val diagnostic = requireNotNull(settlement.failureEvidence?.diagnostic)
    assertTrue(diagnostic.startsWith("Provider turn completed; launcher cleanup failed."))
    assertContains(diagnostic, "Readian")
  }

  @Test
  fun `confirmed exit makes a transient process cleanup warning nonfatal without hiding it`() {
    val process = ShutdownProcess(exitOnEof = true)
    val tree = AgentRunProcessTree(process)
    val degradation = ProcessRunDegradationRecorder()
    degradation.recordCleanupFailure("process_destroy_wait", IllegalStateException("earlier wait failed"))
    val release = lifetime(tree, mutableSetOf(tree), degradation).release(settledWait())

    val settlement = settle(release, degradation.snapshot())

    assertFalse(settlement.failed)
    assertNull(settlement.cleanupFailure)
    assertSame(completed, settlement.outcome)
    assertContains(degradation.appendToStderr(""), "earlier wait failed")
    assertTrue(settle(release.copy(outputCaptureIncomplete = true), degradation.snapshot()).failed)
    degradation.recordCleanupFailure("stdout_stream_close", IllegalStateException("stream close failed"))
    assertTrue(settle(release, degradation.snapshot()).failed)
  }

  private fun settle(
    release: ProcessRunReleaseSnapshot,
    degradation: List<ProcessRunDegradationRecord>,
  ) = sessionSettlement(completed, capture, true, release, degradation)

  private fun settledWait() =
    Result.success(
      ProcessWait(true, false, false, false, sessionSettled = true),
    )

  private fun lifetime(
    tree: AgentRunProcessTree,
    owned: MutableSet<AgentRunProcessTree>,
    degradation: ProcessRunDegradationRecorder,
  ): ProcessRunLifetime {
    fun drain(
      stream: AgentRunOutputStream,
      text: String,
    ) = Utf8Drain(
      ByteArrayInputStream(text.encodeToByteArray()),
      stream,
      AgentRunOutputSink.NONE,
      {},
      awaitWorker = { worker, _ -> worker.join() },
    ).also { it.start() }
    return ProcessRunLifetime(
      tree,
      owned,
      drain(AgentRunOutputStream.STDOUT, "Completed work"),
      drain(AgentRunOutputStream.STDERR, "Readian authentication or upstream request failed"),
      degradation,
    )
  }

  private val completed = AgentRunSessionOutcome("Completed work", "model", "thread", true)
  private val capture = AgentRunSessionCapture("Completed work", true, false, false)
}

private class ShutdownProcess(
  private val exitOnEof: Boolean,
  childSurvives: Boolean = false,
) : Process() {
  val actions = mutableListOf<String>()
  private val child = ShutdownHandle(2, "child", actions, survives = childSurvives)
  private val root = ShutdownHandle(1, "root", actions, descendants = listOf(child))
  private val stdin =
    object : ByteArrayOutputStream() {
      override fun close() {
        actions += "stdin_close"
        if (exitOnEof) {
          child.alive = false
          root.alive = false
        }
      }
    }

  override fun getOutputStream() = stdin

  override fun getInputStream() = ByteArrayInputStream(byteArrayOf())

  override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())

  override fun waitFor(): Int = error("unused")

  override fun exitValue(): Int = if (isAlive) error("still alive") else 0

  override fun destroy() {
    root.destroy()
  }

  override fun destroyForcibly(): Process = apply { root.destroyForcibly() }

  override fun isAlive(): Boolean = root.alive

  override fun toHandle(): ProcessHandle = root
}

private class ShutdownHandle(
  private val id: Long,
  private val label: String,
  private val actions: MutableList<String>,
  private val survives: Boolean = false,
  private val descendants: List<ShutdownHandle> = emptyList(),
) : ProcessHandle {
  var alive = true

  override fun pid(): Long = id

  override fun parent(): Optional<ProcessHandle> = Optional.empty()

  override fun children(): Stream<ProcessHandle> = descendants()

  override fun descendants(): Stream<ProcessHandle> = if (alive) descendants.stream().map { it } else Stream.empty()

  override fun info(): ProcessHandle.Info = error("unused")

  override fun onExit(): CompletableFuture<ProcessHandle> = CompletableFuture.completedFuture(this)

  override fun supportsNormalTermination(): Boolean = true

  override fun destroy(): Boolean = destroyForcibly()

  override fun destroyForcibly(): Boolean {
    actions += "${label}_force"
    if (!survives) alive = false
    return !alive
  }

  override fun isAlive(): Boolean = alive

  override fun compareTo(other: ProcessHandle): Int = id.compareTo(other.pid())
}
