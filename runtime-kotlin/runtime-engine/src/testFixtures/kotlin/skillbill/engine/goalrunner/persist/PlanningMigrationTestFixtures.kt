package skillbill.engine.goalrunner.persist

import skillbill.engine.goalplanning.GoalPlanningMigration
import skillbill.engine.goalplanning.GoalPlanningMigrationImports
import skillbill.infrastructure.contracts.FeatureTaskRuntimeWireArtifactValidator
import skillbill.infrastructure.contracts.workflow.WorkflowStateSchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.ContractFeatureTaskRuntimePhaseOutputMigration
import skillbill.infrastructure.contracts.workflow.goal.ContractGoalPlanningPreparationSourceValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputMigration
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import java.time.Clock
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator as WireArtifactValidator

fun planningMigrationForTest(
  wireValidator: WireArtifactValidator = FeatureTaskRuntimeWireArtifactValidator(),
  outputs: FeatureTaskRuntimePhaseOutputMigration = ContractFeatureTaskRuntimePhaseOutputMigration(),
): GoalPlanningMigration =
  GoalPlanningMigration(
    ContractGoalPlanningPreparationSourceValidator(),
    outputs,
    wireValidator,
    GoalPlanningMigrationImports(
      NoopFeatureTaskRuntimeWorkerSupervisor,
      Clock.systemUTC(),
      outputs,
      WorkflowStateSchemaValidator(),
    ),
    Clock.systemUTC(),
  )
