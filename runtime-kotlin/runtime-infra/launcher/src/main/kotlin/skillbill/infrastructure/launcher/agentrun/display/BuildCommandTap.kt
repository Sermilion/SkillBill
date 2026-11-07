package skillbill.infrastructure.launcher.agentrun.display

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.error.core.catchingNonCancellation
import skillbill.infrastructure.launcher.process.launch.AgentRunOutputEndObserver
import skillbill.infrastructure.launcher.process.launch.BuildCompletionSampleBinding
import skillbill.ports.agentrun.activity.model.AgentRunActivityKind
import skillbill.ports.agentrun.activity.model.AgentRunActivityState
import skillbill.ports.agentrun.model.AgentRunBuildObserver
import skillbill.ports.agentrun.model.AgentRunCommandEvent
import skillbill.ports.agentrun.model.AgentRunCommandEventKind
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.agentrun.model.AgentRunProcessSample
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

internal class SerializedBuildObserver(private val delegate: AgentRunBuildObserver) :
  AgentRunBuildObserver,
  BuildCompletionSampleBinding {
  private val pending = ConcurrentLinkedQueue<Pending>()
  private val bindingLock = Any()
  private var completionSampler: (() -> List<AgentRunProcessSample>)? = null

  override fun bindCompletionSamples(sample: () -> List<AgentRunProcessSample>): AutoCloseable {
    synchronized(bindingLock) { completionSampler = sample }
    return AutoCloseable {
      synchronized(bindingLock) {
        if (completionSampler === sample) completionSampler = null
      }
    }
  }

  override fun command(event: AgentRunCommandEvent) {
    if (event.kind == AgentRunCommandEventKind.STARTED) {
      pending.add(Pending.Event(event))
      return
    }
    val sampler = synchronized(bindingLock) { completionSampler }
    if (sampler == null) {
      pending.add(
        Pending.Event(
          event,
          IllegalStateException("terminal build command arrived without a process sample binding"),
        ),
      )
      return
    }
    val sampled =
      catchingNonCancellation { event.copy(completionProcessSamples = sampler()) }
    pending.add(
      Pending.Event(
        event = sampled.getOrElse { event },
        failure = sampled.exceptionOrNull(),
      ),
    )
  }

  fun offerFailure(failure: Throwable) {
    pending.add(Pending.Failure(failure))
  }

  override fun processes(
    observedAt: Instant,
    samples: List<AgentRunProcessSample>,
  ) {
    deliver { delegate.processes(observedAt, samples) }
  }

  override fun finished(observedAt: Instant) {
    deliver { delegate.finished(observedAt) }
  }

  private inline fun deliver(finalCall: () -> Unit) {
    var firstFailure: Throwable? = null
    while (true) {
      when (val item = pending.poll() ?: break) {
        is Pending.Failure -> if (firstFailure == null) firstFailure = item.failure
        is Pending.Event -> {
          if (firstFailure == null) firstFailure = item.failure
          catchingNonCancellation { delegate.command(item.event) }
            .onFailure { failure -> if (firstFailure == null) firstFailure = failure }
        }
      }
    }
    catchingNonCancellation { finalCall() }
      .onFailure { failure -> if (firstFailure == null) firstFailure = failure }
    firstFailure?.let { throw it }
  }

  private sealed interface Pending {
    data class Event(val event: AgentRunCommandEvent, val failure: Throwable? = null) : Pending

    data class Failure(val failure: Throwable) : Pending
  }
}

internal class BuildCommandTap(
  agent: String,
  private val delegate: AgentRunOutputSink,
  private val observer: SerializedBuildObserver,
  private val clock: Clock,
  diagnostics: RuntimeDiagnostics,
) : AgentRunOutputSink, AgentRunOutputEndObserver {
  private val decoder = ProviderActivityDecoder(agent, diagnostics)
  private val mapper = ObjectMapper()
  private val buffer = StringBuilder()
  private var droppingLongLine = false

  @Synchronized
  override fun write(
    stream: AgentRunOutputStream,
    text: String,
  ) {
    delegate.write(stream, text)
    if (stream != AgentRunOutputStream.STDOUT) return
    catchingNonCancellation {
      if (text == STREAM_END) {
        flushLine()
      } else {
        append(text)
      }
    }.onFailure(observer::offerFailure)
  }

  @Synchronized
  fun finish() {
    catchingNonCancellation { flushLine() }.onFailure(observer::offerFailure)
  }

  @Synchronized
  override fun outputEnded(stream: AgentRunOutputStream) {
    if (stream == AgentRunOutputStream.STDOUT) finish()
  }

  private fun append(text: String) {
    text.forEach { char ->
      if (char == '\n') {
        flushLine()
      } else if (!droppingLongLine) {
        if (buffer.length == ReadableAgentOutputSink.MAX_EVENT_CHARS) {
          buffer.setLength(0)
          droppingLongLine = true
        } else {
          buffer.append(char)
        }
      }
    }
  }

  private fun flushLine() {
    if (!droppingLongLine && buffer.isNotEmpty()) {
      val line = buffer.toString()
      val event =
        try {
          mapper.readTree(line)
        } catch (_: JsonProcessingException) {
          null
        }
      if (event != null) {
        decoder.decode(event).forEach { activity ->
          if (activity.kind == AgentRunActivityKind.COMMAND) {
            val kind =
              when (activity.state) {
                AgentRunActivityState.STARTED -> AgentRunCommandEventKind.STARTED
                AgentRunActivityState.COMPLETED -> AgentRunCommandEventKind.COMPLETED
                AgentRunActivityState.FAILED -> AgentRunCommandEventKind.FAILED
              }
            observer.command(
              AgentRunCommandEvent(
                callId = activity.id,
                command = activity.text,
                kind = kind,
                exitCode = activity.exitCode,
                observedAt = clock.instant(),
              ),
            )
          }
        }
      }
    }
    buffer.setLength(0)
    droppingLongLine = false
  }

  private companion object {
    const val STREAM_END = "\u0000"
  }
}
