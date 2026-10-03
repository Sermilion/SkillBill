package skillbill.di.workflow

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.contracts.workflow.WorkflowStateSchemaValidator
import skillbill.infrastructure.contracts.workflow.decomposition.DecompositionManifestSchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.ContractFeatureTaskRuntimePhaseOutputMigration
import skillbill.infrastructure.contracts.workflow.goal.ContractGoalPlanningPreparationSourceValidator
import skillbill.infrastructure.contracts.workflow.goal.IdeStatusSchemaValidator
import skillbill.ports.goalrunner.GoalPlanningPreparationSourceValidator
import skillbill.ports.idestatus.IdeStatusValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputMigration
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator

internal interface RuntimeWorkflowValidatorProvides {
  @Provides
  fun decompositionManifestValidator(): DecompositionManifestValidator = DecompositionManifestSchemaValidator()

  @Provides
  fun workflowSnapshotValidator(): WorkflowSnapshotValidator = WorkflowStateSchemaValidator()

  @Provides
  fun ideStatusValidator(): IdeStatusValidator = IdeStatusSchemaValidator()

  @Provides
  fun goalPlanningPreparationSourceValidator(
    validator: ContractGoalPlanningPreparationSourceValidator,
  ): GoalPlanningPreparationSourceValidator = validator

  @Provides
  fun featureTaskRuntimePhaseOutputMigration(
    migration: ContractFeatureTaskRuntimePhaseOutputMigration,
  ): FeatureTaskRuntimePhaseOutputMigration = migration
}
