package skillbill.engine.featuretask.lifecycle.execution

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.config.model.RepoLocalConfig
import skillbill.config.model.ValidationGateRepoConfig
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.runner.InMemoryRuntimeWorkflowRepository
import skillbill.engine.featuretask.runner.kotlinPackWithBuildGate
import skillbill.engine.featuretask.runner.kotlinPackWithValidationGate
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.engine.featuretask.slot.testPhaseStrategies
import skillbill.engine.featuretask.validation.ValidationGateResolver
import skillbill.engine.featuretask.validation.repoLocalConfig
import skillbill.engine.goalrunner.persist.planningMigrationForTest
import skillbill.infrastructure.contracts.workflow.featuretask.ContractFeatureTaskRuntimePhaseOutputMigration
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeExecutionPlanSchemaValidator
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.config.model.ReadRepoLocalConfigResult
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path

class ExecutionPlanAdmissionFixture(
  definition: SkeletonDefinition = SkeletonDefinition.STANDALONE,
  private val repository: String = "repo-root-realpath-v1:/tmp/admission-repository",
  private val specPath: String = ".feature-specs/SKILL-384/spec.md",
  private val database: DatabaseSessionFactory = FakeDatabaseSessionFactory(InMemoryRuntimeWorkflowRepository()),
  qualityGate: FeatureTaskRuntimeQualityGateSelection? =
    FeatureTaskRuntimeQualityGateSelection.VALIDATE.takeIf { definition == SkeletonDefinition.GOAL_CHILD },
  selectedStrategies: PhaseStrategyLookup? = null,
  supervisor: FeatureTaskRuntimeWorkerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
) {
  private val routeScope =
    if (definition == SkeletonDefinition.GOAL_CHILD) {
      FeatureTaskRouteScope.GOAL_CHILD
    } else {
      FeatureTaskRouteScope.STANDALONE
    }
  var launches = 0
    private set
  val strategies =
    selectedStrategies ?: testPhaseStrategies(
      GoalRunnerSubtaskLauncher {
        launches++
        error("Admission must not launch a phase")
      },
      NoopWorkflowGitOperations,
    )
  val validator = FeatureTaskRuntimeExecutionPlanSchemaValidator()
  val codec = FeatureTaskRuntimeExecutionPlanCodec(validator)
  val compatibility = FeatureTaskRuntimeExecutionPlanCompatibility(codec, strategies)
  val inputs =
    EffectiveGatePolicyInputs(
      if (qualityGate == FeatureTaskRuntimeQualityGateSelection.BUILD) {
        ValidationGateCommandFamily.BUILD
      } else {
        ValidationGateCommandFamily.VALIDATION
      },
      null,
      null,
      null,
      ValidationDepth.FULL,
      null,
    )
  private val recoveryGateDeclaration =
    requireNotNull(kotlinPackWithValidationGate().validationGate).copy(
      fullGateCommand = listOf("./gradlew", "full"),
      cacheBypassingFullGateCommand = listOf("./gradlew", "full", "--no-cache"),
      collectAllFullGateCommand = listOf("./gradlew", "check", "--continue"),
      cacheBypassingCollectAllFullGateCommand = listOf("./gradlew", "check", "--continue", "--no-cache"),
    )
  val plan =
    strategies.executionPlan(
      PhaseStrategySelectionFacts(
        definition,
        setOfNotNull(
          CodeReviewExecutionMode.INLINE,
          qualityGate,
        ),
      ),
    )
  val encoded = codec.encodeExecution(plan, inputs)

  val admission =
    FeatureTaskRuntimeExecutionAdmission(
      compatibility,
      NoopRuntimeDiagnostics,
      ContractFeatureTaskRuntimePhaseOutputMigration(),
      planningMigrationForTest(),
      supervisor,
    )

  fun seed(
    states: WorkflowStateRepository,
    workflowId: String,
    issueKey: String = "SKILL-384",
    descriptor: Map<String, Any?> = descriptor(),
    executionIdentity: FeatureTaskExecutionIdentity = identity(workflowId, issueKey),
  ) {
    val existing =
      states.getFeatureTaskWorkflow(workflowId)
        ?: WorkflowEngine().openRecord(
          WorkflowFamily.TASK_RUNTIME.definition,
          workflowId,
          "session",
          "implement",
        ).toRecord()
    states.saveFeatureTaskWorkflow(
      existing.copy(
        issueKey = issueKey,
        artifactsJson =
          JsonCodec.mapToJsonString(
            existing.toSnapshot().artifacts +
              DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.entry(descriptor),
          ),
      ),
      FeatureTaskWorkflowMode.RUNTIME,
    )
    states.saveFeatureTaskExecutionIdentity(executionIdentity)
  }

  fun identity(
    workflowId: String,
    issueKey: String = "SKILL-384",
  ) = FeatureTaskExecutionIdentity(
    workflowId,
    issueKey,
    repository,
    specPath,
    FeatureTaskWorkflowMode.RUNTIME,
    routeScope,
  )

  fun creationResolver(): FeatureTaskRuntimeExecutionPlanResolver =
    FeatureTaskRuntimeExecutionPlanResolver(
      strategies,
      codec,
      validator,
      ValidationGateResolver { listOf(kotlinPackWithBuildGate()) },
      object : WorkflowGitOperations by NoopWorkflowGitOperations {
        override fun repositoryOwnedPaths(repoRoot: Path) = WorkflowGitNameListResult.Listed(listOf("src/Main.kt"))
      },
      repoLocalConfig(),
      database,
      compatibility,
    )

  fun recoveryResolver(
    wrapperForRoot: (Path) -> String? = { null },
    onResolve: ((Path) -> Unit)? = null,
  ): FeatureTaskRuntimeExecutionPlanResolver =
    FeatureTaskRuntimeExecutionPlanResolver(
      strategies,
      codec,
      validator,
      ValidationGateResolver {
        listOf(kotlinPackWithValidationGate().copy(validationGate = recoveryGateDeclaration))
      },
      object : WorkflowGitOperations by NoopWorkflowGitOperations {
        override fun repositoryOwnedPaths(repoRoot: Path): WorkflowGitNameListResult.Listed {
          onResolve?.invoke(repoRoot)
          return WorkflowGitNameListResult.Listed(listOf("src/Main.kt"))
        }
      },
      object : RepoLocalConfigPort {
        override fun readRepoLocalConfig(request: ReadRepoLocalConfigRequest) =
          ReadRepoLocalConfigResult(
            RepoLocalConfig.defaults().copy(
              validationGate =
                ValidationGateRepoConfig(
                  gradleWrapper = wrapperForRoot(request.repoRoot).also { onResolve?.invoke(request.repoRoot) },
                ),
            ),
          )
      },
      database,
      compatibility,
    )

  fun encoded(inputs: EffectiveGatePolicyInputs = this.inputs): ByteArray = codec.encodeExecution(plan, inputs)

  fun inputsFor(
    wrapper: String?,
    timeout: Long?,
  ): EffectiveGatePolicyInputs =
    inputs.copy(
      packSlug = "kotlin",
      declaration = recoveryGateDeclaration,
      gradleWrapper = wrapper,
      phaseTimeoutMillis = timeout,
    )

  fun descriptor(inputs: EffectiveGatePolicyInputs = this.inputs): Map<String, Any?> =
    validator.read(encoded(inputs), "test creation")
}
