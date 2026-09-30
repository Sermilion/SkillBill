package skillbill.infrastructure.launcher.process.launch

import skillbill.infrastructure.launcher.process.support.newLauncherSha256Digest
import skillbill.infrastructure.launcher.process.waitloop.ProcessWait
import skillbill.infrastructure.launcher.process.waitloop.decodeAvailable
import skillbill.ports.agentrun.model.AgentRunLivenessSnapshot
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

internal fun String.withTimeoutMessage(
  wait: ProcessWait,
  request: AgentRunProcessRequest,
): String =
  when {
    wait.progressIdleTimedOut -> withProgressTimeoutMessage(request, wait.fileActivityGraceExhausted, wait.liveness)
    wait.wallClockTimedOut -> withWallClockTimeoutMessage(request, wait.liveness)
    else -> this
  }

internal fun String.withProgressTimeoutMessage(
  request: AgentRunProcessRequest,
  fileActivityGraceExhausted: Boolean,
  liveness: AgentRunLivenessSnapshot?,
): String {
  val fileActivityDetail =
    if (fileActivityGraceExhausted) {
      " File activity was observed, but the ${request.timing.fileActivityGraceTimeout} " +
        "file-activity grace window was exhausted."
    } else {
      " No file activity was observed."
    }
  val livenessDetail = liveness.detailsSuffix()
  val message =
    "Agent run stopped after ${request.timing.progressIdleTimeout} " +
      "without durable workflow progress.$fileActivityDetail$livenessDetail"
  return if (isBlank()) message else "$this\n$message"
}

internal fun String.withWallClockTimeoutMessage(
  request: AgentRunProcessRequest,
  liveness: AgentRunLivenessSnapshot?,
): String {
  val message =
    "Agent run stopped after optional wall-clock cap ${request.timing.timeout}.${liveness.detailsSuffix()}"
  return if (isBlank()) message else "$this\n$message"
}

internal fun AgentRunLivenessSnapshot?.detailsSuffix(): String =
  this?.let { snapshot ->
    val detail =
      listOfNotNull(
        snapshot.workflowId?.let { workflowId -> "workflow_id=$workflowId" },
        snapshot.workflowStep?.let { workflowStep -> "step=$workflowStep" },
        snapshot.lastDurableProgressAt?.let { timestamp -> "last_durable_progress_at=$timestamp" },
        snapshot.lastFileActivityAt?.let { timestamp -> "last_file_activity_at=$timestamp" },
        snapshot.lastOutputAt?.let { timestamp -> "last_output_at=$timestamp" },
      ).joinToString(", ")
    if (detail.isBlank()) "" else " Last observations: $detail."
  } ?: ""

internal sealed interface ProcessStart {
  data class Started(val process: Process) : ProcessStart

  data class Failed(val error: Exception) : ProcessStart
}

internal data class Utf8DrainCapture(
  val text: String,
  val bytes: ByteArray,
  val totalByteSize: Long,
  val sha256: String,
  val incomplete: Boolean,
)

internal class Utf8Drain(
  private val input: InputStream,
  internal val outputStream: AgentRunOutputStream,
  internal val outputSink: AgentRunOutputSink,
  internal val onChunkRead: (String) -> Unit,
) {
  private val output = ByteArrayOutputStream(INITIAL_OUTPUT_BUFFER_BYTES)

  internal var totalByteSize = 0L
  internal val digest = newLauncherSha256Digest()

  @Volatile private var workerCompleted = false

  @Volatile internal var workerFailure: Throwable? = null

  @Volatile private var frozen = false

  @Volatile private var frozenCapture: Utf8DrainCapture? = null
  internal val stateLock = Any()
  internal val worker =
    thread(start = false, isDaemon = true, name = "skillbill-agent-run-output-drain") {
      runCatching {
        input.use { stream ->
          val buffer = ByteArray(DEFAULT_DRAIN_BUFFER_BYTES)
          val decoder =
            StandardCharsets.UTF_8.newDecoder()
              .onMalformedInput(CodingErrorAction.REPLACE)
              .onUnmappableCharacter(CodingErrorAction.REPLACE)
          val carry = ByteBuffer.allocate(DEFAULT_DRAIN_BUFFER_BYTES + UTF8_MAX_BYTES_PER_CODE_POINT)
          val decoded = CharBuffer.allocate(DEFAULT_DRAIN_BUFFER_BYTES)
          while (!frozen) {
            val read = stream.read(buffer)
            if (read == -1) {
              break
            }
            val frozenBeforeRead =
              synchronized(stateLock) {
                if (frozen) {
                  true
                } else {
                  totalByteSize += read
                  digest.update(buffer, 0, read)
                  false
                }
              }
            if (frozenBeforeRead) return@use
            carry.put(buffer, 0, read)
            carry.flip()
            decodeAvailable(decoded) { decoder.decode(carry, decoded, false) }
            carry.compact()

            synchronized(stateLock) {
              if (!frozen) output.write(buffer, 0, read)
            }
          }
          if (frozen) return@use
          carry.flip()
          decodeAvailable(decoded) { decoder.decode(carry, decoded, true) }
          decodeAvailable(decoded) { decoder.flush(decoded) }
        }
      }.onFailure { failure ->
        workerFailure = failure
      }.also {
        workerCompleted = true
      }
    }

  fun start() {
    worker.start()
  }

  fun join() {
    worker.join(DRAIN_JOIN_TIMEOUT_MILLIS)
  }

  fun joinAndFreeze(): Boolean {
    worker.join(DRAIN_JOIN_TIMEOUT_MILLIS)
    var incomplete = workerFailure != null || !workerCompleted || worker.isAlive
    if (worker.isAlive) {
      runCatching { input.close() }
      worker.join(DRAIN_JOIN_TIMEOUT_MILLIS)
      incomplete = workerFailure != null || !workerCompleted || worker.isAlive
    }
    freezeCapture(incomplete)
    return incomplete
  }

  fun capture(): Utf8DrainCapture =
    frozenCapture ?: freezeCapture(incomplete = workerFailure != null || !workerCompleted)

  private fun freezeCapture(incomplete: Boolean): Utf8DrainCapture {
    synchronized(stateLock) {
      frozenCapture?.let { return it }
      frozen = true
      val bytes = output.toByteArray()
      val capture =
        Utf8DrainCapture(
          text = String(bytes, StandardCharsets.UTF_8),
          bytes = bytes,
          totalByteSize = totalByteSize,
          sha256 = digest.digest().joinToString("") { "%02x".format(it) },
          incomplete = incomplete || workerFailure != null,
        )
      frozenCapture = capture
      return capture
    }
  }

  fun text(): String = capture().text

  fun bytes(): ByteArray = capture().bytes

  fun totalByteSize(): Long = capture().totalByteSize

  fun contentDigest(): String = capture().sha256
}

internal class OutputObservationTracker(
  private val clock: Clock,
) {
  private val lastObservedMillis = AtomicLong(0L)

  fun markObserved() {
    lastObservedMillis.set(clock.millis())
  }

  fun lastObservedAt(): Instant? =
    lastObservedMillis.get()
      .takeIf { millis -> millis > 0L }
      ?.let(Instant::ofEpochMilli)
}

internal fun parseWorkflowIdAndStep(label: String?): Pair<String?, String?> {
  val text = label?.takeIf(String::isNotBlank) ?: return null to null
  val workflow = Regex("""workflow\s+([^\s;]+)""").find(text)?.groupValues?.getOrNull(1)
  val step = Regex("""step\s+([^\s;]+)""").find(text)?.groupValues?.getOrNull(1)
  return workflow to step
}

internal fun Instant.toIsoUtc(): String =
  DateTimeFormatter.ISO_OFFSET_DATE_TIME
    .format(atOffset(ZoneOffset.UTC))

internal const val DEFAULT_DRAIN_BUFFER_BYTES = 8192
internal const val UTF8_MAX_BYTES_PER_CODE_POINT = 4
internal const val INITIAL_OUTPUT_BUFFER_BYTES = DEFAULT_DRAIN_BUFFER_BYTES
internal const val DRAIN_JOIN_TIMEOUT_MILLIS = 1_000L
internal const val MIN_TIMEOUT_MILLIS = 1L
internal const val MIN_TIMEOUT_NANOS = 1L
internal const val PROGRESS_POLL_INTERVAL_MILLIS = 250L
internal const val DESTROY_WAIT_TIMEOUT_MILLIS = 1_000L
