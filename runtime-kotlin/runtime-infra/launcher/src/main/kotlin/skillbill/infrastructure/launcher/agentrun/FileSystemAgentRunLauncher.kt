package skillbill.infrastructure.launcher.agentrun

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.time.JvmSystemClock
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
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.skeleton.EffectiveLaunchModel
import java.nio.file.Path
import java.time.Clock

class FileSystemAgentRunLauncher internal constructor(
  processRunner: AgentRunProcessRunner,
  executableLookup: ExecutableLookup = PathExecutableLookup(),
  databasePath: Path? = null,
  private val providerEnvironment: Map<String, String> = emptyMap(),
  diagnostics: RuntimeDiagnostics,
  runtimeMcpBin: Path? = null,
  clock: Clock = JvmSystemClock,
) : AgentRunLauncher {
  @Inject
  constructor(
    processRunner: JvmAgentRunProcessRunner,
    databaseSessionFactory: DatabaseSessionFactory,
    executableLookup: ExecutableLookup,
    environment: EnvironmentContext,
    diagnostics: RuntimeDiagnostics,
    clock: Clock,
  ) : this(
    processRunner = processRunner as AgentRunProcessRunner,
    executableLookup = executableLookup,
    databasePath = databaseSessionFactory.resolveDbPath(),
    providerEnvironment = environment.environment,
    diagnostics = diagnostics,
    runtimeMcpBin = resolveRuntimeMcpBin(environment.environment, environment.userHome),
    clock = clock,
  )

  private val adapters: Map<SupportedAgent, ProcessAgentRunAdapter> =
    headlessAgentRunAdapters(
      processRunner,
      executableLookup,
      headlessAgentRunCommandBuilders(AgentRunWorkerBinding(databasePath, runtimeMcpBin), providerEnvironment),
      diagnostics,
      clock,
    )

  override fun resolveLaunchModel(request: AgentRunLaunchModelRequest): EffectiveLaunchModel =
    resolveAgentRunLaunchModel(request, providerEnvironment)

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
