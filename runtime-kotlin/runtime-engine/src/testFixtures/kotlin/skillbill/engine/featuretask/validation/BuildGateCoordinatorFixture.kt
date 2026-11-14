package skillbill.engine.featuretask.validation

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCodec
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCompatibility
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.lifecycle.execution.routingConfigResolutionForTests
import skillbill.engine.featuretask.lifecycle.execution.workflowAgentAssignmentStoreForTests
import skillbill.engine.featuretask.slot.statusProjectionPhaseStrategies
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeExecutionPlanSchemaValidator
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.process.ShutdownHookPort
import skillbill.ports.process.ShutdownHookRegistration
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations

fun FeatureTaskRuntimeBuildGateCoordinator(
  resolver: ValidationGateResolver,
  runner: ValidationGateRunner,
  repoLocalConfig: RepoLocalConfigPort,
  diagnostics: RuntimeDiagnostics,
  shutdownHooks: ShutdownHookPort = NoShutdownHooks,
): FeatureTaskRuntimeBuildGateCoordinator {
  val strategies = statusProjectionPhaseStrategies()
  val validator = FeatureTaskRuntimeExecutionPlanSchemaValidator()
  val codec = FeatureTaskRuntimeExecutionPlanCodec(validator)
  val database = FakeDatabaseSessionFactory(InMemoryWorkflowStates())
  val plans =
    FeatureTaskRuntimeExecutionPlanResolver(
      strategies,
      codec,
      validator,
      resolver,
      NoopWorkflowGitOperations,
      repoLocalConfig,
      database,
      FeatureTaskRuntimeExecutionPlanCompatibility(codec, strategies),
      diagnostics,
      workflowAgentAssignments = workflowAgentAssignmentStoreForTests(database),
      configResolution = routingConfigResolutionForTests(),
    )
  return FeatureTaskRuntimeBuildGateCoordinator(resolver, runner, repoLocalConfig, diagnostics, plans, shutdownHooks)
}

val NoShutdownHooks: ShutdownHookPort = ShutdownHookPort { ShutdownHookRegistration { true } }
