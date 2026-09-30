package skillbill.di.featuretask

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.contracts.ProducerOutputEvidenceSchemaValidator
import skillbill.infrastructure.contracts.RejectedOutputDiagnosticSchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeExecutionPlanSchemaValidator
import skillbill.ports.diagnostics.ProducerOutputEvidenceValidator
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimeExecutionPlanValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.infrastructure.contracts.FeatureTaskRuntimeWireArtifactValidator as FeatureTaskRuntimeWireArtifactSchemaValidator

internal interface RuntimeFeatureTaskValidatorProvides {
  @Provides
  fun featureTaskRuntimeExecutionPlanValidator(): FeatureTaskRuntimeExecutionPlanValidator =
    FeatureTaskRuntimeExecutionPlanSchemaValidator()

  @Provides
  fun featureTaskRuntimeWireArtifactValidator(): FeatureTaskRuntimeWireArtifactValidator =
    FeatureTaskRuntimeWireArtifactSchemaValidator()

  @Provides
  fun rejectedOutputDiagnosticMetadataValidator(): RejectedOutputDiagnosticMetadataValidator =
    RejectedOutputDiagnosticSchemaValidator()

  @Provides
  fun producerOutputEvidenceValidator(): ProducerOutputEvidenceValidator = ProducerOutputEvidenceSchemaValidator()
}
