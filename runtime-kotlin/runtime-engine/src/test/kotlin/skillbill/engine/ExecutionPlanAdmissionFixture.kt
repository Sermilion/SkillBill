package skillbill.engine

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.slot.execution.FeatureTaskRuntimeExecutionAdmission
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.engine.featuretask.slot.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.validation.ValidationGateResolver
import skillbill.engine.featuretask.validation.repoLocalConfig
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import java.nio.file.Path
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.engine.featuretask.slot.execution.EffectiveGatePolicyInputs
import skillbill.engine.featuretask.slot.execution.FeatureTaskRuntimeExecutionPlanCodec
import skillbill.engine.featuretask.slot.execution.FeatureTaskRuntimeExecutionPlanCompatibility
import skillbill.engine.featuretask.slot.testPhaseStrategies
import skillbill.engine.featuretask.validation.model.ValidationGateCommandFamily
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeExecutionPlanSchemaValidator
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.review.context.model.launch.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

class ExecutionPlanAdmissionFixture(
  definition: SkeletonDefinition = SkeletonDefinition.STANDALONE,
  private val repository: String = "repo-root-realpath-v1:/tmp/admission-repository",
  private val specPath: String = ".feature-specs/SKILL-384/spec.md",
  qualityGate: FeatureTaskRuntimeQualityGateSelection? =
    FeatureTaskRuntimeQualityGateSelection.VALIDATE.takeIf { definition == SkeletonDefinition.GOAL_CHILD },
) {
  private val routeScope = if (definition == SkeletonDefinition.GOAL_CHILD) FeatureTaskRouteScope.GOAL_CHILD else FeatureTaskRouteScope.STANDALONE
  var launches = 0
    private set
  val strategies = testPhaseStrategies(
    GoalRunnerSubtaskLauncher {
      launches++
      error("Admission must not launch a phase")
    },
    NoopWorkflowGitOperations,
  )
  val validator = FeatureTaskRuntimeExecutionPlanSchemaValidator()
  val codec = FeatureTaskRuntimeExecutionPlanCodec(validator)
  val compatibility = FeatureTaskRuntimeExecutionPlanCompatibility(codec, strategies)
  val inputs = EffectiveGatePolicyInputs(
    if (qualityGate == FeatureTaskRuntimeQualityGateSelection.BUILD) ValidationGateCommandFamily.BUILD
    else ValidationGateCommandFamily.VALIDATION,
    null, null, null, ValidationDepth.FULL, null,
  )
  private val recoveryGateDeclaration = requireNotNull(kotlinPackWithValidationGate().validationGate).copy(
    fullGateCommand = listOf("./gradlew", "full"),
    cacheBypassingFullGateCommand = listOf("./gradlew", "full", "--no-cache"),
    collectAllFullGateCommand = listOf("./gradlew", "check", "--continue"),
    cacheBypassingCollectAllFullGateCommand = listOf("./gradlew", "check", "--continue", "--no-cache"),
  )
  val plan = strategies.executionPlan(
    PhaseStrategySelectionFacts(definition, setOfNotNull(
      CodeReviewExecutionMode.INLINE,
      qualityGate,
    )),
  )
  val encoded = codec.encodeExecution(plan, inputs)

  val admission = FeatureTaskRuntimeExecutionAdmission(compatibility, NoopRuntimeDiagnostics)

  fun seed(
    states: WorkflowStateRepository,
    workflowId: String,
    issueKey: String = "SKILL-384",
    descriptor: Map<String, Any?> = descriptor(),
    executionIdentity: FeatureTaskExecutionIdentity = identity(workflowId, issueKey),
  ) {
    val existing = states.getFeatureTaskWorkflow(workflowId)
      ?: WorkflowEngine().openRecord(WorkflowFamily.TASK_RUNTIME.definition, workflowId, "session", "implement").toRecord()
    states.saveFeatureTaskWorkflow(existing.copy(
      issueKey = issueKey,
      artifactsJson = JsonCodec.mapToJsonString(existing.toSnapshot().artifacts +
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.entry(descriptor)),
    ), FeatureTaskWorkflowMode.RUNTIME)
    states.saveFeatureTaskExecutionIdentity(executionIdentity)
  }

  fun identity(workflowId: String, issueKey: String = "SKILL-384") = FeatureTaskExecutionIdentity(
    workflowId, issueKey, repository, specPath,
    FeatureTaskWorkflowMode.RUNTIME, routeScope,
  )

  fun creationResolver(): FeatureTaskRuntimeExecutionPlanResolver = FeatureTaskRuntimeExecutionPlanResolver(
    strategies, codec, validator, ValidationGateResolver { listOf(kotlinPackWithBuildGate()) },
    object : WorkflowGitOperations by NoopWorkflowGitOperations {
      override fun repositoryOwnedPaths(repoRoot: Path) = WorkflowGitNameListResult.Listed(listOf("src/Main.kt"))
    },
    repoLocalConfig(),
  )

  fun recoveryResolver(
    wrapperForRoot: (Path) -> String? = { null },
    onResolve: ((Path) -> Unit)? = null,
  ): FeatureTaskRuntimeExecutionPlanResolver = FeatureTaskRuntimeExecutionPlanResolver(
    strategies, codec, validator,
    ValidationGateResolver {
      listOf(kotlinPackWithValidationGate().copy(validationGate = recoveryGateDeclaration))
    },
    object : WorkflowGitOperations by NoopWorkflowGitOperations {
      override fun repositoryOwnedPaths(repoRoot: Path): WorkflowGitNameListResult.Listed {
        onResolve?.invoke(repoRoot)
        return WorkflowGitNameListResult.Listed(listOf("src/Main.kt"))
      }
    },
    object : skillbill.ports.config.RepoLocalConfigPort {
      override fun readRepoLocalConfig(request: skillbill.ports.config.model.ReadRepoLocalConfigRequest) =
        skillbill.ports.config.model.ReadRepoLocalConfigResult(
          skillbill.config.model.RepoLocalConfig.defaults().copy(
            validationGate = skillbill.config.model.ValidationGateRepoConfig(
              gradleWrapper = wrapperForRoot(request.repoRoot),
            ),
          ),
        )
    },
  )

  fun encoded(inputs: EffectiveGatePolicyInputs = this.inputs): ByteArray = codec.encodeExecution(plan, inputs)

  fun inputsFor(wrapper: String?, timeout: Long?): EffectiveGatePolicyInputs = inputs.copy(
    packSlug = "kotlin",
    declaration = recoveryGateDeclaration,
    gradleWrapper = wrapper,
    phaseTimeoutMillis = timeout,
  )

  fun descriptor(inputs: EffectiveGatePolicyInputs = this.inputs): Map<String, Any?> =
    validator.read(encoded(inputs), "test creation")
}
