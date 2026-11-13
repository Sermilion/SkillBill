package skillbill.infrastructure.launcher.agentrun

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.launcher.mcp.resolveRuntimeMcpBin
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRunner
import skillbill.infrastructure.launcher.process.launch.JvmAgentRunProcessRunner
import skillbill.install.model.SupportedAgent
import skillbill.model.EnvironmentContext
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.agentrun.model.AgentRunLaunchModelRequest
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.approval.ApprovalOwnerAuthority
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.system.HostPlatformPort
import skillbill.workflow.taskruntime.model.skeleton.EffectiveLaunchModel
import skillbill.workflow.taskruntime.model.skeleton.passThroughLaunchModel
import java.nio.file.Path
import java.time.Clock

class FileSystemAgentRunLauncher internal constructor(
  processRunner: AgentRunProcessRunner,
  executableLookup: ExecutableLookup = PathExecutableLookup(),
  databasePath: Path? = null,
  private val providerEnvironment: Map<String, String> = emptyMap(),
  diagnostics: RuntimeDiagnostics,
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
