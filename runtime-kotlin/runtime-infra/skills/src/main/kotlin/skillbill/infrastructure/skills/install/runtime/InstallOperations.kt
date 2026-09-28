package skillbill.infrastructure.skills.install.runtime
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.skills.install.apply.applyInstallPlan
import skillbill.infrastructure.skills.install.plan.buildInstallPlan
import skillbill.infrastructure.skills.install.plan.detectAgents
import skillbill.infrastructure.skills.install.plan.resolveInstallEnvironment
import skillbill.infrastructure.skills.install.plan.resolveInstallHome
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.install.model.AgentTarget
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.ports.install.InstallPlanWireValidator
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.system.HostPlatformPort
import skillbill.ports.telemetry.transport.TelemetryConfigStore
import skillbill.ports.telemetry.transport.TelemetryLevelMutator
import java.nio.file.Path
import skillbill.infrastructure.skills.install.plan.codexAgentsPath as planCodexAgentsPath

object InstallOperations {
  fun planInstall(
    request: InstallPlanRequest,
    wireValidator: InstallPlanWireValidator,
  ): InstallPlan = buildInstallPlan(request, wireValidator)

  fun applyInstall(
    plan: InstallPlan,
    telemetryLevelMutator: TelemetryLevelMutator? = null,
    telemetryConfigStore: TelemetryConfigStore? = null,
    mcpRegistrationPort: InstallMcpRegistrationPort,
    catalogLoader: PlatformPackCatalogLoader? = null,
  ): InstallApplyResult =
    applyInstallPlan(
      plan,
      telemetryLevelMutator,
      telemetryConfigStore,
      mcpRegistrationPort,
      catalogLoader,
    )

  fun agentPath(
    agent: String,
    home: Path?,
    environment: Map<String, String>,
    hostPlatform: HostPlatformPort = JdkHostPlatformPort,
  ): Path = InstallOperationsPaths.agentPath(agent, home, environment, hostPlatform)

  fun detectAgentTargets(
    home: Path?,
    environment: Map<String, String>,
    hostPlatform: HostPlatformPort = JdkHostPlatformPort,
  ): List<AgentTarget> {
    val resolvedHome = resolveInstallHome(home, hostPlatform)
    val resolvedEnvironment = resolveInstallEnvironment(environment, hostPlatform)
    return detectAgents(resolvedHome, resolvedEnvironment)
  }

  fun claudeRoots(
    home: Path?,
    environment: Map<String, String>,
    hostPlatform: HostPlatformPort = JdkHostPlatformPort,
  ): List<Path> = InstallOperationsPaths.claudeRoots(home, environment, hostPlatform)

  fun codexAgentsPath(
    home: Path?,
    environment: Map<String, String>,
    hostPlatform: HostPlatformPort = JdkHostPlatformPort,
  ): Path {
    val resolvedHome = resolveInstallHome(home, hostPlatform)
    return planCodexAgentsPath(resolvedHome, environment)
  }

  fun codexRoots(
    home: Path?,
    environment: Map<String, String>,
    hostPlatform: HostPlatformPort = JdkHostPlatformPort,
  ): List<Path> = InstallOperationsPaths.codexRoots(home, environment, hostPlatform)

  fun claudeAgentsPath(
    home: Path?,
    environment: Map<String, String>,
    hostPlatform: HostPlatformPort = JdkHostPlatformPort,
  ): Path = InstallOperationsPaths.claudeAgentsPath(home, environment, hostPlatform)

  fun junieAgentsPath(
    home: Path?,
    hostPlatform: HostPlatformPort = JdkHostPlatformPort,
  ): Path = InstallOperationsPaths.junieAgentsPath(home, hostPlatform)

  fun cursorAgentsPath(
    home: Path?,
    hostPlatform: HostPlatformPort = JdkHostPlatformPort,
  ): Path = InstallOperationsPaths.cursorAgentsPath(home, hostPlatform)
}
