package skillbill.engine.featuretask.validation.check

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.agentrun.model.AgentRunMcpStartupProbe
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.LiveChecksAvailability
import skillbill.ports.idestatus.model.LiveChecksWriteMode
import skillbill.ports.idestatus.model.StandaloneNarrowWriteResult

@Inject
public class ValidationLiveChecks(
  private val database: DatabaseSessionFactory,
  private val diagnostics: RuntimeDiagnostics,
) {
  public fun beforeLaunch(
    invocationId: String,
    agentCapable: Boolean,
  ) {
    val mode = if (agentCapable) LiveChecksWriteMode.KEEP_OR_UNAVAILABLE else LiveChecksWriteMode.UNAVAILABLE
    write(invocationId, mode, "launch_mark")
  }

  public fun startupProbe(invocationId: String): AgentRunMcpStartupProbe =
    AgentRunMcpStartupProbe {
      catchingNonCancellation {
        database.transaction { unit -> unit.standalonePhaseStatuses.readByInvocation(invocationId)?.liveChecks }
      }.fold(
        onSuccess = { it == LiveChecksAvailability.AVAILABLE },
        onFailure = { failure ->
          warn("startup_probe_failed", invocationId, failure)
          false
        },
      )
    }

  public fun markServerStarted(invocationId: String) {
    write(invocationId, LiveChecksWriteMode.AVAILABLE, "startup_mark")
  }

  private fun write(
    invocationId: String,
    mode: LiveChecksWriteMode,
    kind: String,
  ) {
    catchingNonCancellation {
      database.transaction { unit -> unit.standalonePhaseStatuses.writeLiveChecks(invocationId, mode) }
    }.fold(
      onSuccess = { result ->
        when (result) {
          StandaloneNarrowWriteResult.WRITTEN -> Unit
          StandaloneNarrowWriteResult.MISSING -> warn("${kind}_missing", invocationId)
          StandaloneNarrowWriteResult.FINISHED -> warn("${kind}_finished", invocationId)
        }
      },
      onFailure = { failure -> warn("${kind}_failed", invocationId, failure) },
    )
  }

  private fun warn(
    kind: String,
    invocationId: String,
    cause: Throwable? = null,
  ) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=validation_check kind=$kind invocation=${bounded(invocationId)}",
      cause,
    )
  }

  private fun bounded(invocationId: String): String =
    invocationId.take(MAX_LOGGED_INVOCATION_LENGTH).replace(UNSAFE_LOG_CHARACTER, "_")

  private companion object {
    const val MAX_LOGGED_INVOCATION_LENGTH = 128
    val UNSAFE_LOG_CHARACTER = Regex("[^A-Za-z0-9._-]")
  }
}
