package skillbill.engine.featuretask.validation

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCodec
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCompatibility
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.slot.statusProjectionPhaseStrategies
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeExecutionPlanSchemaValidator
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations

fun FeatureTaskRuntimeBuildGateCoordinator(
  resolver: ValidationGateResolver,
  runner: ValidationGateRunner,
  repoLocalConfig: RepoLocalConfigPort,
  diagnostics: RuntimeDiagnostics,
): FeatureTaskRuntimeBuildGateCoordinator {
  val strategies = statusProjectionPhaseStrategies()
  val validator = FeatureTaskRuntimeExecutionPlanSchemaValidator()
  val codec = FeatureTaskRuntimeExecutionPlanCodec(validator)
  val plans =
    FeatureTaskRuntimeExecutionPlanResolver(
      strategies, codec, validator, resolver, NoopWorkflowGitOperations, repoLocalConfig,
      FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
      FeatureTaskRuntimeExecutionPlanCompatibility(codec, strategies), diagnostics,
    )
  return FeatureTaskRuntimeBuildGateCoordinator(resolver, runner, repoLocalConfig, diagnostics, plans)
}
