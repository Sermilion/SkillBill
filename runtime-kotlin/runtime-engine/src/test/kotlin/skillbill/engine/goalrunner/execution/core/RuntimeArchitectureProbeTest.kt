package skillbill.engine.goalrunner.execution.core

import skillbill.engine.featuretask.validation.coordinator
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerLedgerRecorder
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.telemetry.GoalRunnerProgressEventEmitter
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.agentrun.model.AgentRunProgressEmission
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.process.DaemonThreadPort
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.process.ShutdownHookPort
import skillbill.ports.process.ShutdownHookRegistration
import skillbill.ports.taskruntime.FeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RuntimeArchitectureProbeTest {
  private val clock = Clock.fixed(Instant.parse("2026-09-16T10:00:00Z"), ZoneOffset.UTC)

  private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
      invoke(method.name, args ?: emptyArray())
    } as T

  private class State {
    var lease: GoalRunnerExecutionLease? = null
    var stopped = false
    var unregistered = false
    var bodyInvoked = false
  }

  private fun coordinator(
    state: State,
    heartbeatStartFailure: Throwable? = null,
    hookStartFailure: Throwable? = null,
    hookStopFailure: Throwable? = null,
    heartbeatStopFailure: Throwable? = null,
  ): DefaultGoalRunnerExecutionCoordinator {
    val store =
      proxy<GoalRunnerManifestStore> { name, args ->
        when (name) {
          "executionLease" -> state.lease
          "acquireExecutionLease" -> {
            state.lease = args[1] as GoalRunnerExecutionLease
            true
          }
          "releaseExecutionLease" -> {
            state.lease = null
            true
          }
          else -> unexpectedCall(name)
        }
      }
    val supervisor =
      proxy<FeatureTaskRuntimeWorkerSupervisor> { name, _ ->
        when (name) {
          "currentProcess" -> FeatureTaskRuntimeProcessIdentity("host", "boot", 1, "birth")
          "startHeartbeat" -> {
            heartbeatStartFailure?.let(::throwFailure)
            object : FeatureTaskRuntimeHeartbeat {
              override fun stop() {
                heartbeatStopFailure?.let(::throwFailure)
                state.stopped = true
              }

              override fun fencingLostReason(): String? = null
            }
          }
          else -> unexpectedCall(name)
        }
      }
    val hooks =
      ShutdownHookPort {
        hookStartFailure?.let(::throwFailure)
        ShutdownHookRegistration {
          hookStopFailure?.let(::throwFailure)
          state.unregistered = true
          true
        }
      }
    val daemon = proxy<DaemonThreadPort> { _, _ -> unexpectedCall("No shutdown execution expected") }
    return DefaultGoalRunnerExecutionCoordinator(
      store,
      supervisor,
      clock,
      hooks,
      daemon,
      IdentifierGeneratorPort { "owner" },
    )
  }

  @Test
  fun `probe heartbeat startup failure releases acquired lease`() {
    val state = State()
    val failure = IllegalStateException("start heartbeat")
    assertSame(
      failure,
      assertFails {
        coordinator(state, heartbeatStartFailure = failure).runOwned("parent") {
          state.bodyInvoked = true
          error("body")
        }
      },
    )
    assertNull(state.lease)
    assertFalse(state.bodyInvoked)
  }

  @Test
  fun `probe hook registration failure cleans up heartbeat and lease`() {
    val state = State()
    val failure = IllegalStateException("register hook")
    assertSame(
      failure,
      assertFails {
        coordinator(state, hookStartFailure = failure).runOwned("parent") {
          state.bodyInvoked = true
          error("body")
        }
      },
    )
    assertNull(state.lease)
    assertTrue(state.stopped)
    assertFalse(state.bodyInvoked)
  }

  @Test
  fun `probe hook cleanup preserves primary failure and continues cleanup`() {
    val state = State()
    val primary = IllegalArgumentException("body")
    val secondary = IllegalStateException("unregister hook")
    val thrown =
      assertFails {
        coordinator(state, hookStopFailure = secondary).runOwned("parent") {
          throw primary
        }
      }
    assertSame(primary, thrown)
    assertTrue(thrown.suppressed.any { it === secondary })
    assertNull(state.lease)
    assertTrue(state.stopped)
  }

  @Test
  fun `probe heartbeat cleanup failure still releases lease`() {
    val state = State()
    val secondary = IllegalStateException("stop heartbeat")
    assertSame(
      secondary,
      assertFails {
        coordinator(state, heartbeatStopFailure = secondary).runOwned("parent") { "done" }
      },
    )
    assertTrue(state.unregistered)
    assertNull(state.lease)
  }

  @Test
  fun `probe progress resolver cancellation propagates`() {
    val diagnostics = proxy<RuntimeDiagnostics> { _, _ -> null }
    val store = proxy<GoalRunnerWorkflowOutcomeStore> { _, _ -> error("Store should not be called") }
    val emitter =
      GoalRunnerProgressEventEmitter(
        store,
        { throw CancellationException("stop") },
        "SKILL-56",
        clock,
        diagnostics,
      )
    assertFailsWithCancellation {
      emitter.emit(AgentRunProgressEmission(GoalProgressEventKind.entries.first(), true, "probe", "probe"))
    }
  }

  @Test
  fun `probe ledger watermark read failure stops construction`() {
    val diagnostics = proxy<RuntimeDiagnostics> { _, _ -> null }
    val store =
      proxy<GoalRunnerWorkflowOutcomeStore> { name, _ ->
        when (name) {
          "ledgerSequenceWatermarks" -> error("read failed")
          else -> unexpectedCall(name)
        }
      }
    assertFails {
      GoalRunnerLedgerRecorder(
        store,
        GoalRunnerRunRequest("SKILL-248", Path.of("."), "codex"),
        clock,
        diagnostics,
      )
    }
  }

  private inline fun assertFailsWithCancellation(block: () -> Unit) {
    try {
      block()
      check(false) { "Expected CancellationException" }
    } catch (_: CancellationException) {
    }
  }

  private fun unexpectedCall(name: String): Nothing = error("Unexpected call $name")

  private fun throwFailure(failure: Throwable): Nothing = throw failure
}
