package skillbill.engine.featuretask.runloop.durable

import me.tatarka.inject.annotations.Inject
import skillbill.agent.buildrun.BuildCommandMatcher
import skillbill.agent.buildrun.BuildTracker
import skillbill.agent.model.BuildCommandEvent
import skillbill.agent.model.BuildCommandEventKind
import skillbill.agent.model.BuildMatchContext
import skillbill.agent.model.BuildOutcome
import skillbill.agent.model.BuildProcessSample
import skillbill.agent.model.BuildRepositoryFiles
import skillbill.agent.model.BuildTrackerEvent
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.runloop.observability.AgentBuildObserverFactory
import skillbill.error.core.catchingNonCancellation
import skillbill.model.EnvironmentContext
import skillbill.ports.agentrun.model.AgentRunBuildObserver
import skillbill.ports.agentrun.model.AgentRunCommandEvent
import skillbill.ports.agentrun.model.AgentRunCommandEventKind
import skillbill.ports.agentrun.model.AgentRunProcessSample
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.AgentBuildRunRepository
import skillbill.ports.idestatus.model.AgentBuildRunStart
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.UUID
import skillbill.ports.idestatus.model.AgentBuildOutcome as StoredBuildOutcome

@Inject
public class AgentBuildRecorderFactory(
  private val store: AgentBuildRunRepository,
  private val repositoryRoots: RepositoryEnclosingRootPort,
  private val catalog: InstalledPlatformPackCatalogPort,
  private val files: BuildRepositoryFiles,
  private val environment: EnvironmentContext,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) : AgentBuildObserverFactory {
  public override fun observer(
    repoRoot: Path,
    resolveRunId: () -> String?,
    resolveParentRunId: () -> String?,
  ): AgentRunBuildObserver {
    val manifests =
      catchingNonCancellation { catalog.manifests() }
        .getOrElse { failure ->
          warnSetup(failure)
          return AgentRunBuildObserver.NONE
        }
    val repositoryIdentity =
      catchingNonCancellation { repositoryRoots.primaryRepositoryIdentity(repoRoot) }
        .getOrElse { failure ->
          warnSetup(failure)
          return AgentRunBuildObserver.NONE
        }
    if (manifests.none { it.buildTools.isNotEmpty() }) return AgentRunBuildObserver.NONE
    return AgentBuildRecorder(
      repositoryIdentity = repositoryIdentity,
      resolveRunId = resolveRunId,
      resolveParentRunId = resolveParentRunId,
      store = store,
      matcher =
        BuildCommandMatcher(
          manifests,
          BuildMatchContext(repoRoot.toString(), environment.environment[DEVELOPER_DIR]),
          MemoizedBuildRepositoryFiles(files),
        ),
      clock = clock,
      diagnostics = diagnostics,
    )
  }

  private fun warnSetup(failure: Throwable) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=agent_build_recorder_setup run_id=unresolved value_expected=resolved " +
        "value_used=disabled cause=${failure.javaClass.simpleName}",
      failure,
    )
  }

  private companion object {
    const val DEVELOPER_DIR = "DEVELOPER_DIR"
  }
}

internal class AgentBuildRecorder(
  private val repositoryIdentity: String,
  private val resolveRunId: () -> String?,
  private val resolveParentRunId: () -> String?,
  private val store: AgentBuildRunRepository,
  private val matcher: BuildCommandMatcher,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) : AgentRunBuildObserver {
  private var runId: String? = null
  private var parentRunId: String? = null
  private var runResolutionWarningEmitted = false
  private var unresolvedFinishWarningEmitted = false
  private var abandonAttempted = false
  private var tracker = BuildTracker(clock)
  private val buildIds = mutableMapOf<BuildIdentity, String>()
  private val pending = mutableListOf<BuildTrackerEvent>()

  override fun processes(
    observedAt: Instant,
    samples: List<AgentRunProcessSample>,
  ) {
    resolveRunIdentity()?.let { id ->
      if (!abandonAttempted) {
        abandonAttempted = true
        guarded("abandon_open") { store.abandonOpen(id) }
        drainPending()
      }
    }
    val matches =
      guarded("match") {
        matcher.match(samples.map { it.toDomain() })
      } ?: return
    val events = guarded("match") { tracker.observe(matches, observedAt) } ?: emptyList()
    events.forEach(::acceptEvent)
  }

  override fun command(event: AgentRunCommandEvent) {
    val domainEvent =
      BuildCommandEvent(
        callId = event.callId,
        commandText = event.command,
        kind =
          when (event.kind) {
            AgentRunCommandEventKind.STARTED -> BuildCommandEventKind.STARTED
            AgentRunCommandEventKind.COMPLETED -> BuildCommandEventKind.COMPLETED
            AgentRunCommandEventKind.FAILED -> BuildCommandEventKind.FAILED
          },
        exitCode = event.exitCode,
        observedAt = event.observedAt,
        completionProcessSamples = event.completionProcessSamples?.map { it.toDomain() },
      )
    val events = guarded("match") { tracker.command(domainEvent) } ?: emptyList()
    events.forEach(::acceptEvent)
  }

  override fun finished(observedAt: Instant) {
    val events = guarded("match") { tracker.finish(observedAt) } ?: emptyList()
    pending += events
    if (resolveRunIdentity() != null) {
      drainPending()
      buildIds.clear()
    } else if (pending.isNotEmpty() && !unresolvedFinishWarningEmitted) {
      unresolvedFinishWarningEmitted = true
      warn(
        "op=finish run_id=unresolved value_expected=attributed value_used=retained " +
          "pending_events=${pending.size}",
      )
    }
    tracker = BuildTracker(clock)
    abandonAttempted = false
  }

  private fun resolveRunIdentity(): String? {
    runId?.let { return it }
    val resolved =
      catchingNonCancellation { resolveRunId()?.takeIf(String::isNotBlank) }.fold(
        onSuccess = { it },
        onFailure = { failure ->
          if (!runResolutionWarningEmitted) {
            runResolutionWarningEmitted = true
            warn(
              "op=resolve_run run_id=unresolved value_expected=resolved value_used=retry " +
                "cause=${failure.javaClass.simpleName}",
              failure,
            )
          }
          null
        },
      ) ?: return null
    runId = resolved
    parentRunId = guarded("resolve_parent") { resolveParentRunId()?.takeIf(String::isNotBlank) }
    return resolved
  }

  private fun acceptEvent(event: BuildTrackerEvent) {
    if (runId == null) pending += event else handleEvent(event)
  }

  private fun drainPending() {
    val events = pending.iterator()
    while (events.hasNext()) {
      handleEvent(events.next())
      events.remove()
    }
  }

  private fun handleEvent(event: BuildTrackerEvent) {
    val id = runId ?: return
    when (event) {
      is BuildTrackerEvent.Started -> {
        val buildId = UUID.randomUUID().toString()
        buildIds[BuildIdentity(event.pid, event.startedAt)] = buildId
        guarded("start") {
          store.start(
            AgentBuildRunStart(
              buildId = buildId,
              runId = id,
              parentRunId = parentRunId,
              repositoryIdentity = repositoryIdentity,
              command = event.displayCommand,
              shortCommand = event.shortCommand,
              tool = event.toolLabel,
              projectKind = event.projectKind,
              pid = event.pid,
              startedAt = event.startedAt,
            ),
          )
        }
      }
      is BuildTrackerEvent.Settled -> {
        val identity = BuildIdentity(event.pid, event.startedAt)
        val buildId = buildIds[identity] ?: return
        guarded("settle") {
          store.settle(
            buildId,
            event.endedAt,
            when (event.outcome) {
              BuildOutcome.PASSED -> StoredBuildOutcome.PASSED
              BuildOutcome.FAILED -> StoredBuildOutcome.FAILED
              BuildOutcome.UNKNOWN -> StoredBuildOutcome.UNKNOWN
            },
            event.exitCode,
          )
        }
        buildIds.remove(identity)
      }
    }
  }

  private inline fun <T> guarded(
    op: String,
    call: () -> T,
  ): T? =
    catchingNonCancellation(call).fold(
      onSuccess = { it },
      onFailure = { failure ->
        val id = runId ?: "unresolved"
        warn(
          "op=$op run_id=$id value_expected=persisted value_used=skipped " +
            "cause=${failure.javaClass.simpleName}",
          failure,
        )
        null
      },
    )

  private fun warn(
    message: String,
    failure: Throwable? = null,
  ) {
    RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "seam=agent_build_record $message", failure)
  }

  private fun AgentRunProcessSample.toDomain() = BuildProcessSample(pid, parentPid, command, arguments, startedAt)

  private data class BuildIdentity(val pid: Long, val startedAt: Instant)
}

private class MemoizedBuildRepositoryFiles(private val delegate: BuildRepositoryFiles) : BuildRepositoryFiles {
  private val exists = mutableMapOf<String, Boolean>()
  private val contents = mutableMapOf<String, String?>()

  override fun exists(path: String): Boolean = exists.getOrPut(path) { delegate.exists(path) }

  override fun readText(path: String): String? {
    if (path !in contents) contents[path] = delegate.readText(path)
    return contents[path]
  }
}
