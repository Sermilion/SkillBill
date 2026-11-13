package skillbill.engine.featuretask.validation

import skillbill.application.testHarnessClock
import skillbill.contracts.JsonCodec
import skillbill.contracts.config.projectchecks.ProjectCheckCategoryConfig
import skillbill.contracts.config.projectchecks.ProjectCheckCommand
import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.runner.kotlinPackWithBuildGate
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.engine.featuretask.validation.model.ValidationGateCycleResult
import skillbill.engine.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.engine.featuretask.validation.projectchecks.parserMetadataFromDeclaration
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.validation.model.ValidationGateCommandSource
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AdmittedBuildGateCommandsTest {
  @Test
  fun `routing wrapper and mutable manifest changes cannot replace the admitted build or its receipt command`() {
    val root = Files.createTempDirectory("admitted-build-command")
    try {
      val execution = ExecutionPlanAdmissionFixture(SkeletonDefinition.GOAL_CHILD)
      val database = phaseRunDatabase(root, testHarnessClock)
      val argv = mutableListOf("./gradlew", "compileKotlin")
      val declaration =
        validationGateTestDeclaration.copy(
          buildCommand = argv,
          cacheBypassingBuildCommand = listOf("./gradlew", "compileKotlin", "--rerun-tasks"),
        )
      val inputs =
        EffectiveGatePolicyInputs(
          ValidationGateCommandFamily.BUILD,
          "kotlin",
          declaration,
          "admitted/gradlew",
          ValidationDepth.FULL,
          null,
        )
      val plan =
        execution.strategies.executionPlan(
          PhaseStrategySelectionFacts(
            SkeletonDefinition.GOAL_CHILD,
            setOf(
              CodeReviewExecutionMode.INLINE,
              FeatureTaskRuntimeQualityGateSelection.BUILD,
            ),
          ),
        )
      val descriptor = execution.validator.read(execution.codec.encodeExecution(plan, inputs), "build creation")
      val request = minimalRequest()
      val admitted =
        admitGate(database, execution, request, inputs, descriptor)
      argv[1] = "changed-task"
      val runner = ScriptedGateRunner(listOf(passed()))
      val coordinator =
        buildFeatureTaskRuntimeBuildGateCoordinator(
          outOfContractResolver(),
          runner,
          repoLocalConfig("changed/gradlew"),
          NoopRuntimeDiagnostics,
        )
      val result =
        coordinator.execute(
          outOfContractCycle().copy(
            request = request.copy(admittedExecution = admitted),
            changedPaths = listOf("ios/Changed.swift"),
          ),
        )

      assertEquals(listOf("admitted/gradlew", "-p", "admitted", "compileKotlin"), runner.requests.single().argv)
      assertEquals(listOf("./gradlew", "compileKotlin"), runner.requests.single().declaration?.buildCommand)
      val completed =
        assertIs<ValidationGateCycleTerminalOutcome.Completed>(
          assertIs<ValidationGateCycleResult.Terminal>(result).outcome,
        )
      assertEquals(true, completed.output.payload.contains("admitted/gradlew -p admitted compileKotlin"))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `admitted project check snapshot keeps saved argv after pack routing changes`() {
    val root = Files.createTempDirectory("admitted-build-snapshot")
    try {
      val snapshot =
        ProjectCheckCommandSnapshot(
          projectId = "proj-1",
          buildCommands =
            ProjectCheckCategoryConfig(
              listOf(ProjectCheckCommand(listOf("./gradlew", "compileKotlin"), ".")),
            ),
          validationCommands = null,
          parserMetadata = parserMetadataFromDeclaration("kotlin", validationGateTestDeclaration),
          gradleWrapper = "admitted/gradlew",
        ).frozen()
      val execution =
        ExecutionPlanAdmissionFixture(
          SkeletonDefinition.GOAL_CHILD,
          qualityGate = FeatureTaskRuntimeQualityGateSelection.BUILD,
        )
      val plan = execution.plan.withProjectCheckCommands(snapshot)
      val inputs =
        EffectiveGatePolicyInputs(
          ValidationGateCommandFamily.BUILD,
          "kotlin",
          validationGateTestDeclaration,
          "admitted/gradlew",
          ValidationDepth.FULL,
          null,
          projectCheckSnapshot = snapshot,
        ).frozen()
      val database = phaseRunDatabase(root, testHarnessClock)
      val request = minimalRequest()
      val descriptor = execution.validator.read(execution.codec.encodeExecution(plan, inputs), "build snapshot")
      val admitted = admitGate(database, execution, request, inputs, descriptor)
      val runner = ScriptedGateRunner(listOf(passed()))
      buildFeatureTaskRuntimeBuildGateCoordinator(
        ValidationGateResolver { listOf(kotlinPackWithBuildGate()) },
        runner,
        repoLocalConfig("changed/gradlew"),
        NoopRuntimeDiagnostics,
      ).execute(
        outOfContractCycle().copy(
          request = request.copy(admittedExecution = admitted),
          changedPaths = listOf("ios/Changed.swift"),
        ),
      )
      assertEquals(ValidationGateCommandSource.PROJECT_CONFIG, runner.requests.single().commandSource)
      assertEquals(listOf("./gradlew", "compileKotlin"), runner.requests.single().argv)
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private fun admitGate(
    database: DatabaseSessionFactory,
    execution: ExecutionPlanAdmissionFixture,
    request: FeatureTaskRuntimeRunRequest,
    inputs: EffectiveGatePolicyInputs,
    descriptor: Map<String, Any?>,
  ) = database.transaction { unit ->
    val row =
      WorkflowEngine().openRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        request.workflowId,
        "session",
        "build",
      ).toRecord().copy(issueKey = request.issueKey)
    unit.workflowStates.saveFeatureTaskWorkflow(
      row.copy(
        artifactsJson =
          JsonCodec.mapToJsonString(
            row.toSnapshot().artifacts +
              DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.entry(
                descriptor,
              ),
          ),
      ),
      FeatureTaskWorkflowMode.RUNTIME,
    )
    unit.workflowStates.saveFeatureTaskExecutionIdentity(
      execution.identity(request.workflowId, request.issueKey)
        .copy(routeScope = FeatureTaskRouteScope.GOAL_CHILD),
    )
    execution.admission.admit(unit.workflowStates, request.workflowId, inputs)
  }
}
