package skillbill.infrastructure.launcher.agentrun

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.launcher.mcp.resolveRuntimeMcpBin
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRunner
import skillbill.infrastructure.launcher.process.launch.JvmAgentRunProcessRunner
import skillbill.install.model.AGENT_LAUNCHER_CLIS
import skillbill.install.model.AgentLauncherCli
import skillbill.install.model.SupportedAgent
import skillbill.install.model.agentLauncherUnavailableMessage
import skillbill.model.EnvironmentContext
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.agentrun.model.AgentRunLaunchCapability
import skillbill.ports.agentrun.model.AgentRunLaunchCapabilityRequest
import skillbill.ports.agentrun.model.AgentRunLaunchModelRequest
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.AgentRunLocalAvailability
import skillbill.ports.agentrun.model.AgentRunLocalAvailabilityRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.approval.ApprovalOwnerAuthority
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.system.HostPlatformPort
import skillbill.workflow.taskruntime.model.skeleton.EffectiveLaunchModel
import skillbill.workflow.taskruntime.model.skeleton.passThroughLaunchModel
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Clock

class FileSystemAgentRunLauncher internal constructor(
  processRunner: AgentRunProcessRunner,
  private val executableLookup: ExecutableLookup = PathExecutableLookup(),
  databasePath: Path? = null,
  private val providerEnvironment: Map<String, String> = emptyMap(),
  private val diagnostics: RuntimeDiagnostics,
  clock: Clock,
  runtimeMcpBin: Path? = null,
  approvals: AgentRunApprovalOwners? = null,
  userHome: Path? = null,
  hostPlatform: HostPlatformPort? = null,
) : AgentRunLauncher {
  @Inject
  constructor(
    processRunner: JvmAgentRunProcessRunner,
    databaseSessionFactory: DatabaseSessionFactory,
    executableLookup: ExecutableLookup,
    environment: EnvironmentContext,
    diagnostics: RuntimeDiagnostics,
    approvalOwners: ApprovalOwnerAuthority,
    clock: Clock,
    hostPlatform: HostPlatformPort,
  ) : this(
    processRunner = processRunner as AgentRunProcessRunner,
    executableLookup = executableLookup,
    databasePath = databaseSessionFactory.resolveDbPath(),
    providerEnvironment = environment.environment,
    diagnostics = diagnostics,
    clock = clock,
    runtimeMcpBin = resolveRuntimeMcpBin(environment.environment, environment.userHome),
    approvals = AgentRunApprovalOwners(approvalOwners, clock),
    userHome = environment.userHome.takeUnless { it == EnvironmentContext.UnspecifiedUserHome },
    hostPlatform = hostPlatform,
  )

  private val adapters: Map<SupportedAgent, ProcessAgentRunAdapter> =
    headlessAgentRunAdapters(
      processRunner,
      executableLookup,
      AgentRunWorkerBinding(databasePath, runtimeMcpBin, approvals, userHome, providerEnvironment),
      diagnostics,
      clock,
    )

  override fun resolveLaunchModel(request: AgentRunLaunchModelRequest): EffectiveLaunchModel =
    passThroughLaunchModel(request.requestedModel, request.requestedEffort)

  override fun queryLaunchCapability(request: AgentRunLaunchCapabilityRequest): AgentRunLaunchCapability {
    val agent =
      SupportedAgent.fromWireOrNull(request.agentId)
        ?: return AgentRunLaunchCapability.Unsupported("Agent '${request.agentId}' is not supported.")
    return if (adapters.containsKey(agent)) {
      AgentRunLaunchCapability.Supported
    } else {
      AgentRunLaunchCapability.Unsupported(
        "Agent '${agent.id}' does not have a supported ${request.environmentKind.wireValue} launch path.",
      )
    }
  }

  override fun queryLocalAvailability(request: AgentRunLocalAvailabilityRequest): AgentRunLocalAvailability {
    val agent = request.agent
    val launcher = AGENT_LAUNCHER_CLIS[agent]
    val unsupportedReason =
      when {
        !adapters.containsKey(agent) ->
          "Agent '${agent.id}' does not have a supported headless launch path."
        launcher == null -> "No launcher executables are registered for '${agent.id}'."
        else -> null
      }
    if (unsupportedReason != null) {
      diagnostics.warning("seam=agent_run outcome=local_availability_unsupported provider=${agent.id}")
      return AgentRunLocalAvailability.Unsupported(agent, unsupportedReason)
    }
    return executableAvailability(agent, checkNotNull(launcher))
  }

  private fun executableAvailability(
    agent: SupportedAgent,
    launcher: AgentLauncherCli,
  ): AgentRunLocalAvailability {
    var executable: String? = null
    var readFailure: Exception? = null
    try {
      executable = launcher.executables.firstOrNull { executableLookup.onPath(it) }
    } catch (failure: IOException) {
      readFailure = failure
    } catch (failure: SecurityException) {
      readFailure = failure
    } catch (failure: InvalidPathException) {
      readFailure = failure
    }
    return when {
      readFailure != null -> unavailableExecutableRead(agent, readFailure)
      executable == null -> {
        diagnostics.warning("seam=agent_run outcome=local_availability_missing provider=${agent.id}")
        AgentRunLocalAvailability.ExecutableMissing(
          agent,
          agentLauncherUnavailableMessage(agent, launcher.executables.first(), launcher.installHint),
        )
      }
      else -> AgentRunLocalAvailability.Registered(agent, executable)
    }
  }

  private fun unavailableExecutableRead(
    agent: SupportedAgent,
    failure: Exception,
  ): AgentRunLocalAvailability.Unavailable {
    diagnostics.warning(
      "seam=agent_run outcome=local_availability_unreadable provider=${agent.id}",
      failure,
    )
    return AgentRunLocalAvailability.Unavailable(
      agent,
      "Could not check the '${agent.id}' launch executable: ${failure.message ?: failure.javaClass.simpleName}",
    )
  }

  override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
    val agent = SupportedAgent.fromNormalizedId(request.agentId)
    val adapter =
      adapters[agent]
        ?: return UnsupportedAgentRunLaunch(
          agent = agent,
          reason = "Agent '${agent.id}' does not have a supported headless feature runtime launch path.",
        )
    return adapter.launch(request.skillRunRequest)
  }
}
