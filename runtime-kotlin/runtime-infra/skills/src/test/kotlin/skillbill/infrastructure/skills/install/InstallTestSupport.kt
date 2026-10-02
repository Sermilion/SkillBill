package skillbill.infrastructure.skills.install

import skillbill.infrastructure.contracts.install.InstallPlanSchemaValidator
import skillbill.infrastructure.host.FileTelemetryConfigStore
import skillbill.infrastructure.skills.install.mcp.McpRegistrationOperations
import skillbill.infrastructure.skills.install.runtime.InstallOperations
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.model.EnvironmentContext
import skillbill.model.toPath
import skillbill.ports.install.InstallPlanWireValidator
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.install.mcp.model.InstallMcpRegistrationRequest
import skillbill.ports.install.mcp.model.InstallMcpRegistrationResult
import skillbill.ports.install.mcp.model.InstallMcpUnregistrationRequest
import skillbill.ports.telemetry.transport.TelemetryLevelMutator

internal val installTestWireValidator: InstallPlanWireValidator = InstallPlanSchemaValidator()

private class InstallTestMcpRegistrationPort(
  private val environment: Map<String, String>,
) : InstallMcpRegistrationPort {
  override fun registerMcp(request: InstallMcpRegistrationRequest): InstallMcpRegistrationResult =
    InstallMcpRegistrationResult(
      outcome =
        McpRegistrationOperations.register(
          request.agent,
          request.runtimeMcpBin,
          request.home,
          environment,
        ),
    )

  override fun unregisterMcp(request: InstallMcpUnregistrationRequest): InstallMcpRegistrationResult =
    InstallMcpRegistrationResult(
      outcome =
        McpRegistrationOperations.unregister(
          request.agent,
          request.home,
          environment,
        ),
    )
}

internal fun planInstallForTest(request: InstallPlanRequest): InstallPlan =
  InstallOperations.planInstall(request, installTestWireValidator)

internal fun applyInstallForTest(
  plan: InstallPlan,
  telemetryLevelMutator: TelemetryLevelMutator? = null,
): InstallApplyResult {
  val environment = plan.request.environment.ifEmpty { installTestEnvironment(plan.request.home.toPath()) }
  val environmentContext =
    EnvironmentContext(
      environment = environment,
      userHome = plan.request.home.toPath(),
    )
  return InstallOperations.applyInstall(
    plan,
    telemetryLevelMutator,
    FileTelemetryConfigStore(environmentContext),
    InstallTestMcpRegistrationPort(environment),
  )
}
