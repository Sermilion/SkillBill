package skillbill.infrastructure.launcher.process.launch

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import skillbill.contracts.time.JvmSystemClock
import skillbill.infrastructure.host.jvm.hostPath
import skillbill.infrastructure.host.jvm.testGateJvmResolver
import skillbill.infrastructure.launcher.process.waitloop.ProcessWait
import skillbill.infrastructure.launcher.process.waitloop.writeAndCloseStdin
import skillbill.infrastructure.launcher.review.GovernedReviewEvidenceEndpoint
import skillbill.ports.agentrun.model.AgentRunMcpStartupProbe
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.AgentRunProgressProbe
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorizationResult
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointHandle
import skillbill.ports.review.evidence.ReviewEvidenceBroker
import skillbill.ports.review.model.GovernedReviewEvidenceEndpointDescriptor
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewToolCall
import skillbill.review.context.model.hunk.ReviewExpansionRecord
import skillbill.review.context.model.launch.ReviewConversationIsolation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class JvmAgentRunProcessRunnerTest {
  private val trackedChildPids = mutableListOf<Long>()
  private val trackedChildPidFiles = mutableListOf<Path>()

  @AfterEach
  fun destroyTrackedChildren() {
    trackedChildPids.forEach { pid ->
      runCatching {
        ProcessHandle.of(pid).ifPresent { handle ->
          if (handle.isAlive) {
            handle.destroyForcibly()
          }
        }
      }
    }
    trackedChildPids.clear()
    trackedChildPidFiles.forEach { pidFile ->
      runCatching { Files.readString(pidFile).trim().toLong() }.getOrNull()?.let { pid ->
        ProcessHandle.of(pid).ifPresent { handle ->
          if (handle.isAlive) {
            handle.destroyForcibly()
          }
        }
      }
      runCatching { Files.deleteIfExists(pidFile) }
    }
    trackedChildPidFiles.clear()
  }

  @Test
  fun `throwing output sink still reaps a live child process`() {
    val pidFile = Files.createTempFile("skillbill-child", ".pid")
    trackedChildPidFiles.add(pidFile)
    val sink =
      AgentRunOutputSink { stream, _ ->
        if (stream == AgentRunOutputStream.STDERR) {
          error("output sink failed")
        }
      }
    assertThrows<IllegalStateException> {
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "echo $$ > '$pidFile'; exec sleep 120"),
          Path.of("."),
        ) {
          outputSink = sink
          timeout = 30.seconds
          statusHeartbeatInterval = 1.milliseconds
        },
      )
    }
    val pid = Files.readString(pidFile).trim().toLong()
    trackedChildPids.add(pid)
    Files.deleteIfExists(pidFile)
    assertFalse(childProcessAlive(pid), "F-001: child must not outlive runner cleanup")
  }

  @Test
  fun `probe failure is recorded without resetting the idle deadline`() {
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "sleep 5"),
          Path.of("."),
        ) {
          timeout = 2.seconds
          progressIdleTimeout = 100.milliseconds
          progressProbe = AgentRunProgressProbe { error("probe failed") }
        },
      )

    assertTrue(result.timedOut)
    assertTrue(result.stderr.contains("probe_failure"))
    assertTrue(result.stderr.contains("progress_token"))
  }

  @Test
  fun `progress absence after an observation does not extend the idle deadline`() {
    var observations = 0
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "sleep 5"),
          Path.of("."),
        ) {
          timeout = 2.seconds
          progressIdleTimeout = 100.milliseconds
          progressProbe =
            AgentRunProgressProbe {
              if (observations++ == 0) "observed" else null
            }
        },
      )

    assertTrue(result.timedOut)
    assertTrue(result.stderr.contains("probe_absence"))
    assertTrue(result.stderr.contains("progress_token"))
  }

  @Test
  fun `stdin delivery failure is reported without using the output sink`() {
    val recorder = ProcessRunDegradationRecorder()

    writeAndCloseStdin(FakeReapableProcess(staysAlive = false), "input", recorder)

    val diagnostic = recorder.appendToStderr("")
    assertTrue(diagnostic.contains("stdin_delivery_failure"))
    assertTrue(diagnostic.contains("stdin"))
  }

  @Test
  fun `cancellation from a probe keeps its primary identity while cleanup releases the child`() {
    val primary = CancellationException("cancelled")
    assertSame(
      primary,
      assertThrows<CancellationException> {
        JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
          testAgentRunProcessRequest(
            listOf("sh", "-c", "exec sleep 120"),
            Path.of("."),
          ) {
            progressProbe = AgentRunProgressProbe { throw primary }
          },
        )
      },
    )
  }

  @Test
  fun `setup cancellation still releases the child process`() {
    val pidFile = Files.createTempFile("skillbill-child-setup-cancel", ".pid")
    trackedChildPidFiles.add(pidFile)
    Files.deleteIfExists(pidFile)
    assertThrows<CancellationException> {
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "echo $$ > '$pidFile'; exec sleep 120"),
          Path.of("."),
        ) {
          mcpStartupProbe =
            AgentRunMcpStartupProbe {
              val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
              while (
                (!Files.exists(pidFile) || Files.readString(pidFile).trim().isBlank()) &&
                System.nanoTime() < deadline
              ) {
                Thread.sleep(10)
              }
              check(Files.exists(pidFile) && Files.readString(pidFile).trim().isNotBlank())
              throw CancellationException("cancelled during setup")
            }
        },
      )
    }
    val pid = Files.readString(pidFile).trim().toLong()
    trackedChildPids.add(pid)
    Files.deleteIfExists(pidFile)
    assertFalse(childProcessAlive(pid), "F-002: child must not outlive setup cancellation cleanup")
  }

  @Test
  fun `endpoint close failure falls back to logger when stderr sink also fails`() {
    val logger = Logger.getLogger("skillbill.agent.run.teardown")
    val sinkWrites = AtomicInteger(0)
    val sink =
      AgentRunOutputSink { _, _ ->
        sinkWrites.incrementAndGet()
        error("sink rejected teardown diagnostic")
      }
    val endpoint =
      object : GovernedReviewEvidenceEndpointHandle {
        override val descriptor =
          GovernedReviewEvidenceEndpointDescriptor(
            lane = "test",
            socketPath = Path.of("/tmp/test-review.sock"),
            mcpConfigPath = Path.of("/tmp/test-review.json"),
            token = "test-token",
          )

        override fun close() {
          error("endpoint-close-failure")
        }
      }
    val (_, messages) =
      capturingLogRecords(logger) {
        JvmAgentRunProcessRunner.closeEndpoint(
          testAgentRunProcessRequest(listOf("true"), Path.of(".")) {
            outputSink = sink
            reviewEvidenceEndpoint = endpoint
            reviewEvidenceBroker = TeardownProbeBroker
            conversationIsolation = ReviewConversationIsolation.FRESH
          },
        )
      }
    assertEquals(1, sinkWrites.get())
    assertTrue(
      messages.any { it.contains("endpoint teardown failed") && it.contains("endpoint-close-failure") },
      messages.toString(),
    )
  }

  @Test
  fun `degradation export falls back to logger when stderr sink also fails`() {
    val logger = Logger.getLogger("skillbill.agent.run.degradation")
    val degradation = ProcessRunDegradationRecorder()
    degradation.recordCleanupFailure("stdout_stream_close", IllegalStateException("close-failure"))
    val sink = AgentRunOutputSink { _, _ -> error("sink rejected degradation diagnostic") }

    val (_, messages) =
      capturingLogRecords(logger) {
        exportRunDegradationEvidence(degradation, sink)
      }

    assertTrue(
      messages.any { it.contains("degradation export failed") && it.contains("stdout_stream_close") },
      messages.toString(),
    )
  }

  @Test
  fun `cancellation from a probe is not converted into missing progress`() {
    assertThrows<CancellationException> {
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "exec sleep 120"),
          Path.of("."),
        ) {
          progressProbe = AgentRunProgressProbe { throw CancellationException("cancelled") }
        },
      )
    }
  }

  @Test
  fun `drain join timeout reports incomplete capture instead of settled evidence`() {
    val blocking = BlockingInputStream()
    val drain =
      Utf8Drain(
        input = blocking,
        outputStream = AgentRunOutputStream.STDOUT,
        outputSink = AgentRunOutputSink.NONE,
        onChunkRead = {},
      )
    drain.start()
    val incomplete = drain.joinAndFreeze()
    assertTrue(incomplete)
    val capture = drain.capture()
    assertTrue(capture.incomplete)
    val firstDigest = capture.sha256
    blocking.unblock()
    drain.joinAndFreeze()
    assertEquals(firstDigest, drain.capture().sha256)
  }

  @Test
  fun `interrupt during drain settlement remains set after release`() {
    val stdoutInput = BlockingInputStream()
    val stderrInput = BlockingInputStream()
    val stdout = testDrain(stdoutInput)
    val stderr = testDrain(stderrInput)
    stdout.start()
    stderr.start()
    val process = FakeReapableProcess(staysAlive = false, streamsAvailable = true)
    val liveProcesses = mutableSetOf<Process>(process)
    val degradation = ProcessRunDegradationRecorder()
    val lifetime =
      ProcessRunLifetime(
        process = process,
        liveProcesses = liveProcesses,
        stdout = stdout,
        stderr = stderr,
        degradation = degradation,
      )
    val releaseThread =
      thread(start = true) {
        lifetime.release(
          Result.success(
            ProcessWait(
              finished = true,
              progressIdleTimedOut = false,
              fileActivityGraceExhausted = false,
              wallClockTimedOut = false,
            ),
          ),
        )
      }
    assertTrue(stdoutInput.awaitReadStarted())
    releaseThread.interrupt()
    releaseThread.join(5_000)
    stdoutInput.unblock()
    stderrInput.unblock()
    stdout.join()
    stderr.join()

    assertFalse(releaseThread.isAlive)
    assertTrue(releaseThread.isInterrupted)
    assertFalse(liveProcesses.contains(process))
    val stderrChunks = mutableListOf<String>()
    exportRunDegradationEvidence(
      degradation,
      AgentRunOutputSink { stream, chunk ->
        if (stream == AgentRunOutputStream.STDERR) {
          stderrChunks += chunk
        }
      },
    )
    assertTrue(stderrChunks.joinToString("").contains("stdout_drain_join"))
  }

  @Test
  fun `parent interrupt during wait keeps interrupted result without idle timeout`() {
    val runner = JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver())
    var result: AgentRunProcessResult? = null
    val worker =
      thread(start = true) {
        result =
          runner.run(
            testAgentRunProcessRequest(
              listOf("sh", "-c", "sleep 120"),
              Path.of(".").toAbsolutePath().normalize(),
            ) {
              timeout = 120.seconds
              progressIdleTimeout = 120.seconds
            },
          )
      }
    Thread.sleep(200)
    worker.interrupt()
    worker.join(10_000)
    assertFalse(worker.isAlive)
    val completed = requireNotNull(result)
    assertTrue(completed.interrupted)
    assertFalse(completed.timedOut)
    assertEquals("parent_interrupted", completed.liveness?.reason)
  }

  @Test
  fun `large agent streams retain and forward every byte through completion`() {
    val directory = Files.createTempDirectory("agent-large-output")
    try {
      val expected = "BEGIN\n" + "agent output é\n".repeat(160_000) + "TERMINAL\n"
      val payload = directory.resolve("output.txt")
      Files.writeString(payload, expected)
      val stdout = StringBuilder()
      val stderr = StringBuilder()
      val result =
        JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
          testAgentRunProcessRequest(
            listOf("sh", "-c", "cat output.txt; cat output.txt >&2"),
            directory,
          ) {
            outputSink =
              AgentRunOutputSink { stream, chunk ->
                when (stream) {
                  AgentRunOutputStream.STDOUT -> stdout.append(chunk)
                  AgentRunOutputStream.STDERR -> stderr.append(chunk)
                }
              }
          },
        )

      assertEquals(0, result.exitStatus)
      assertFalse(result.stdoutTruncated)
      assertFalse(result.outputCaptureIncomplete)
      assertEquals(expected, result.stdout)
      assertEquals(expected, result.stderr)
      assertEquals(expected, stdout.toString())
      assertEquals(expected, stderr.toString())
      assertEquals(expected.encodeToByteArray().size.toLong(), result.stdoutByteSize)
      assertEquals(expected.encodeToByteArray().size, result.stdoutBytes.size)
    } finally {
      directory.toFile().deleteRecursively()
    }
  }

  @Test
  fun `foreground process result is returned once as the bounded terminal result`() {
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "printf terminal-result"),
          Path.of("."),
        ),
      )

    assertEquals(0, result.exitStatus)
    assertEquals("terminal-result", result.stdout)
    assertEquals(false, result.timedOut)
    assertEquals(false, result.interrupted)
    assertEquals(false, result.spawnFailed)
    assertEquals(true, result.processStarted)
  }

  @Test
  fun `nonzero child exit remains an ordinary completed process result`() {
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "printf failure >&2; exit 17"),
          Path.of("."),
        ),
      )

    assertEquals(17, result.exitStatus)
    assertFalse(result.timedOut)
    assertFalse(result.spawnFailed)
    assertTrue(result.stderr.contains("failure"))
  }

  @Test
  fun `spawn refusal remains distinct from a started process`() {
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("/skillbill/does-not-exist"),
          Path.of("."),
        ),
      )

    assertTrue(result.spawnFailed)
    assertFalse(result.processStarted)
    assertNull(result.exitStatus)
  }

  @Test
  fun `MCP startup is counted only when an explicit launcher probe observes it`() {
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "printf terminal-result"),
          Path.of("."),
        ) {
          mcpStartupProbe = AgentRunMcpStartupProbe { true }
        },
      )

    assertTrue(result.mcpStartupObserved)
  }

  @Test
  fun `spawn authorization surrounds process creation and not terminal waiting`() {
    var authorizationEntered = false
    var authorizationExited = false
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "printf terminal-result"),
          Path.of("."),
        ) {
          spawnAuthorization =
            object : AgentRunSpawnAuthorization {
              override fun <T> withAuthorization(spawn: () -> T): AgentRunSpawnAuthorizationResult<T> {
                authorizationEntered = true
                return AgentRunSpawnAuthorizationResult.Authorized(spawn().also { authorizationExited = true })
              }
            }
        },
      )

    assertEquals(0, result.exitStatus)
    assertEquals("terminal-result", result.stdout)
    assertEquals(true, authorizationEntered)
    assertEquals(true, authorizationExited)
    assertNull(result.spawnDenied)
  }

  @Test
  fun `a denied spawn authorization starts no process and reports the pause reason`() {
    val marker = Files.createTempFile("skillbill-denied-spawn", ".marker")
    Files.delete(marker)
    val denied = AgentRunSpawnAuthorizationResult.Denied("operator_request")
    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "touch '$marker'"),
          Path.of("."),
        ) {
          spawnAuthorization =
            object : AgentRunSpawnAuthorization {
              override fun <T> withAuthorization(spawn: () -> T): AgentRunSpawnAuthorizationResult<T> = denied
            }
        },
      )

    assertEquals(denied, result.spawnDenied)
    assertFalse(Files.exists(marker))
  }

  @Test
  fun `reap destroys a process that exits and does not forcibly kill it`() {
    val process = FakeReapableProcess(staysAlive = false)

    JvmAgentRunProcessRunner.reapLiveProcesses(listOf(process))

    assertEquals(1, process.destroyCount)
    assertEquals(0, process.forcibleCount)
  }

  @Test
  fun `reap forcibly kills a process that stays alive after destroy`() {
    val process = FakeReapableProcess(staysAlive = true)

    JvmAgentRunProcessRunner.reapLiveProcesses(listOf(process))

    assertEquals(1, process.destroyCount)
    assertEquals(1, process.forcibleCount)
  }

  @Test
  fun `isolated launch keeps the agent locatable and its user installation resolvable`() {
    val parent =
      mapOf(
        "HOME" to "/home/dev",
        "PATH" to "/usr/bin",
        "CLAUDE_CONFIG_DIR" to "/home/dev/.claude-work",
        "XDG_CONFIG_HOME" to "/home/dev/.config",
        "ANTHROPIC_SESSION_SECRET" to "ambient",
        "SOME_CALLER_STATE" to "ambient",
      )

    val isolated = isolatedLaunchEnvironment(parent, mapOf("SKILL_BILL_GOAL_CONTINUATION" to "1"))

    assertEquals("/home/dev", isolated["HOME"])
    assertEquals("/usr/bin", isolated["PATH"])
    assertEquals("/home/dev/.claude-work", isolated["CLAUDE_CONFIG_DIR"])
    assertEquals("/home/dev/.config", isolated["XDG_CONFIG_HOME"])
    assertEquals("1", isolated["SKILL_BILL_GOAL_CONTINUATION"])
    assertNull(isolated["ANTHROPIC_SESSION_SECRET"])
    assertNull(isolated["SOME_CALLER_STATE"])
  }

  @Test
  fun `isolated launch overrides win over inherited passthrough values`() {
    val isolated =
      isolatedLaunchEnvironment(
        mapOf("HOME" to "/home/dev", "PATH" to "/usr/bin"),
        mapOf("HOME" to "/tmp/sandbox-home"),
      )

    assertEquals("/tmp/sandbox-home", isolated["HOME"])
    assertEquals("/usr/bin", isolated["PATH"])
  }

  @Test
  fun `isolated launch passes through additional keys declared by the command builder`() {
    val parent =
      mapOf(
        "HOME" to "/home/dev",
        "PATH" to "/usr/bin",
        "ANTHROPIC_API_KEY" to "sk-ant-ambient",
        "SOME_AMBIENT_SECRET" to "should-be-stripped",
      )

    val isolated =
      isolatedLaunchEnvironment(
        parent,
        overrides = mapOf("SKILL_BILL_GOAL_CONTINUATION" to "1"),
        additionalPassthroughKeys = setOf("ANTHROPIC_API_KEY"),
      )

    assertEquals("sk-ant-ambient", isolated["ANTHROPIC_API_KEY"])
    assertEquals("/home/dev", isolated["HOME"])
    assertNull(isolated["SOME_AMBIENT_SECRET"])
  }

  @Test
  fun `configureLaunchEnvironment applies isolation to a real ProcessBuilder environment map`() {
    val builder = ProcessBuilder("echo", "test")
    builder.environment().clear()
    builder.environment()["HOME"] = "/home/dev"
    builder.environment()["PATH"] = "/usr/bin"
    builder.environment()["ANTHROPIC_API_KEY"] = "sk-ant-ambient"
    builder.environment()["SOME_AMBIENT_SECRET"] = "should-be-stripped"

    configureLaunchEnvironment(
      builder,
      testAgentRunProcessRequest(
        listOf("echo"),
        Path.of("."),
      ) {
        environment = mapOf("SKILL_BILL_GOAL_CONTINUATION" to "1")
        inheritEnvironment = false
        environmentPassthroughKeys = setOf("ANTHROPIC_API_KEY")
      },
      testGateJvmResolver(),
    )

    assertEquals("/home/dev", builder.environment()["HOME"])
    assertEquals("/usr/bin", builder.environment()["PATH"])
    assertEquals("sk-ant-ambient", builder.environment()["ANTHROPIC_API_KEY"])
    assertEquals("1", builder.environment()["SKILL_BILL_GOAL_CONTINUATION"])
    assertNull(builder.environment()["SOME_AMBIENT_SECRET"])
  }

  @Test
  fun `an inherited JAVA_HOME inside the runtime image never reaches the child environment`() {
    val leaked = Path.of(System.getProperty("java.home")).resolve("lib").toString()
    val builder = ProcessBuilder("echo", "test")
    builder.environment().clear()
    builder.environment()["PATH"] = hostPath()
    builder.environment()["JAVA_HOME"] = leaked

    configureLaunchEnvironment(
      builder,
      testAgentRunProcessRequest(listOf("echo"), Path.of(".")) { inheritEnvironment = true },
      testGateJvmResolver(),
    )

    assertNotEquals(leaked, builder.environment()["JAVA_HOME"])
  }

  @Test
  fun `a timed-out governed launch leaves no endpoint bound`() {
    val endpoint =
      GovernedReviewEvidenceEndpoint.bind(
        "architecture",
        TeardownProbeBroker,
        listOf("/bin/true"),
      )

    val result =
      JvmAgentRunProcessRunner(JvmSystemClock, testGateJvmResolver()).run(
        testAgentRunProcessRequest(
          listOf("sh", "-c", "sleep 30"),
          Path.of("."),
        ) {
          timeout = 1.seconds
          conversationIsolation = ReviewConversationIsolation.FRESH
          reviewEvidenceBroker = TeardownProbeBroker
          reviewEvidenceEndpoint = endpoint
        },
      )

    assertTrue(result.timedOut)
    assertTrue(Files.notExists(endpoint.descriptor.socketPath))
    assertTrue(Files.notExists(endpoint.descriptor.mcpConfigPath))
  }

  private object TeardownProbeBroker : ReviewEvidenceBroker {
    override fun authorizeExpansion(request: ReviewExpansionAuthorizationRequest): ReviewExpansionRecord =
      error("unused")

    override fun readBatch(request: ReviewEvidenceBatchRequest) = error("unused")

    override fun recordToolCall(call: ReviewToolCall) = error("unused")

    override fun recordModelTurn() = null

    override fun validateLaneResult(result: String) = null

    override fun observeLaneResultChunk(chunk: String) = null

    override fun accounting() =
      ReviewLaneAccounting(
        lane = "architecture",
        evidenceBytes = 0,
        expansions = emptyList(),
        toolCalls = 0,
        modelTurns = 0,
        resultBytes = 0,
      )

    override fun terminalOutcome() = null
  }

  private fun childProcessAlive(pid: Long): Boolean =
    ProcessHandle.of(pid).map { handle -> handle.isAlive }.orElse(false)

  private fun <T> capturingLogRecords(
    logger: Logger,
    block: () -> T,
  ): Pair<T, List<String>> {
    val records = mutableListOf<LogRecord>()
    val handler =
      object : Handler() {
        override fun publish(record: LogRecord) {
          records += record
        }

        override fun flush() = Unit

        override fun close() = Unit
      }
    logger.addHandler(handler)
    return try {
      block() to records.mapNotNull(LogRecord::getMessage)
    } finally {
      logger.removeHandler(handler)
    }
  }

  private fun testDrain(input: InputStream) =
    Utf8Drain(
      input = input,
      outputStream = AgentRunOutputStream.STDOUT,
      outputSink = AgentRunOutputSink.NONE,
      onChunkRead = {},
    )
}

private class BlockingInputStream : InputStream() {
  @Volatile
  private var open = true
  private val readStarted = CountDownLatch(1)

  override fun read(): Int {
    readStarted.countDown()
    while (open) {
      Thread.sleep(10)
    }
    return -1
  }

  override fun read(
    buffer: ByteArray,
    offset: Int,
    length: Int,
  ): Int {
    readStarted.countDown()
    while (open) {
      Thread.sleep(10)
    }
    return -1
  }

  fun unblock() {
    open = false
  }

  fun awaitReadStarted(): Boolean = readStarted.await(5, TimeUnit.SECONDS)
}

private class FakeReapableProcess(
  private val staysAlive: Boolean,
  private val streamsAvailable: Boolean = false,
) : Process() {
  var destroyCount = 0
    private set
  var forcibleCount = 0
    private set

  override fun getOutputStream() = if (streamsAvailable) ByteArrayOutputStream() else error("unused")

  override fun getInputStream() = if (streamsAvailable) ByteArrayInputStream(ByteArray(0)) else error("unused")

  override fun getErrorStream() = if (streamsAvailable) ByteArrayInputStream(ByteArray(0)) else error("unused")

  override fun waitFor(): Int = error("unused")

  override fun exitValue(): Int = error("unused")

  override fun destroy() {
    destroyCount++
  }

  override fun destroyForcibly(): Process {
    forcibleCount++
    return this
  }

  override fun isAlive(): Boolean = staysAlive

  override fun waitFor(
    timeout: Long,
    unit: TimeUnit,
  ): Boolean = !staysAlive
}
