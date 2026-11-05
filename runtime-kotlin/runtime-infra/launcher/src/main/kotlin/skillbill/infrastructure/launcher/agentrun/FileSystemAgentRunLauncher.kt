package skillbill.infrastructure.launcher.agentrun

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.launcher.mcp.resolveRuntimeMcpBin
import skillbill.infrastructure.launcher.process.launch.AgentRunProcessRunner
import skillbill.infrastructure.launcher.process.launch.JvmAgentRunProcessRunner
import skillbill.install.model.SupportedAgent
import skillbill.model.EnvironmentContext
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.agentrun.model.AgentRunApprovalRequirement
import skillbill.ports.agentrun.model.AgentRunLaunchModelRequest
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.approval.ApprovalOwnerAuthority
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
  approvals: AgentRunApprovalOwners? = null,
  userHome: Path? = null,
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
  ) : this(
    processRunner = processRunner as AgentRunProcessRunner,
    executableLookup = executableLookup,
    databasePath = databaseSessionFactory.resolveDbPath(),
    providerEnvironment = environment.environment,
    diagnostics = diagnostics,
    runtimeMcpBin = resolveRuntimeMcpBin(environment.environment, environment.userHome),
    approvals = AgentRunApprovalOwners(approvalOwners, clock),
    userHome = environment.userHome.takeUnless { it == EnvironmentContext.UnspecifiedUserHome },
  )

  private val adapters: Map<SupportedAgent, ProcessAgentRunAdapter> =
    headlessAgentRunAdapters(
      processRunner,
      executableLookup,
      AgentRunWorkerBinding(databasePath, runtimeMcpBin, approvals, userHome),
      providerEnvironment,
      diagnostics,
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
    approvalRefusal(agent, adapter, request.skillRunRequest.approval)?.let { return it }
    return adapter.launch(request.skillRunRequest)
  }

  private fun approvalRefusal(
    agent: SupportedAgent,
    adapter: ProcessAgentRunAdapter,
    approval: AgentRunApprovalRequirement,
  ): UnsupportedAgentRunLaunch? =
    when (approval) {
      is AgentRunApprovalRequirement.Unowned ->
        UnsupportedAgentRunLaunch(
          agent = agent,
          reason =
            "Agent '${agent.id}' launch requires operator approval facts; ${approval.reason}, so it was not started.",
        )
      is AgentRunApprovalRequirement.Owned ->
        UnsupportedAgentRunLaunch(
          agent = agent,
          reason =
            "Agent '${agent.id}' cannot host operator approval cards, so card-required execution is unavailable " +
              "on it. Use the claude agent.",
        ).takeUnless { adapter.canHostApprovalCards }
      AgentRunApprovalRequirement.GovernedReview,
      AgentRunApprovalRequirement.RuntimeHosted,
      AgentRunApprovalRequirement.OutsideApproval,
      -> null
    }
}
