package skillbill.di.featuretask

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.contracts.BlockIncidentSchemaValidator
import skillbill.infrastructure.contracts.ProducerOutputEvidenceSchemaValidator
import skillbill.infrastructure.contracts.RejectedOutputDiagnosticSchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeExecutionPlanSchemaValidator
import skillbill.ports.blockincident.BlockIncidentContractValidator
import skillbill.ports.diagnostics.ProducerOutputEvidenceValidator
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputMigration
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.taskruntime.executionplan.FeatureTaskRuntimeExecutionPlanValidator
import skillbill.infrastructure.contracts.workflow.featuretask.wire.FeatureTaskRuntimeWireArtifactValidator as RuntimeFeatureTaskWireArtifactSchemaValidator

internal interface RuntimeFeatureTaskValidatorProvides {
  @Provides
  fun featureTaskRuntimeExecutionPlanValidator(): FeatureTaskRuntimeExecutionPlanValidator =
    FeatureTaskRuntimeExecutionPlanSchemaValidator()

  @Provides
  fun featureTaskRuntimeWireArtifactValidator(
    executionPlans: FeatureTaskRuntimeExecutionPlanValidator,
    phaseOutputs: FeatureTaskRuntimePhaseOutputMigration,
  ): FeatureTaskRuntimeWireArtifactValidator =
    RuntimeFeatureTaskWireArtifactSchemaValidator(executionPlans, phaseOutputs)

  @Provides
  fun rejectedOutputDiagnosticMetadataValidator(): RejectedOutputDiagnosticMetadataValidator =
    RejectedOutputDiagnosticSchemaValidator()

  @Provides
  fun producerOutputEvidenceValidator(): ProducerOutputEvidenceValidator = ProducerOutputEvidenceSchemaValidator()

  @Provides
  fun blockIncidentContractValidator(): BlockIncidentContractValidator = BlockIncidentSchemaValidator()
}
