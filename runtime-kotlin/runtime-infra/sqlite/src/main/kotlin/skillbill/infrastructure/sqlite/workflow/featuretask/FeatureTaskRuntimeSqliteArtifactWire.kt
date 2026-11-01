package skillbill.infrastructure.sqlite.workflow.featuretask

import skillbill.contracts.JsonCodec
import skillbill.workflow.taskruntime.artifact.asTelemetryPayload
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodePhaseOutputRepairEvidenceFromArtifact
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeDiagnosticDegradationMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProjectionMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRejectionMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence

internal fun FeatureTaskRuntimePhaseOutputRepairEvidence.encodeWorkflowArtifact(): Any = asWorkflowArtifactEntry()

internal fun decodePhaseOutputRepairEvidence(json: String): FeatureTaskRuntimePhaseOutputRepairEvidence? =
  decodePhaseOutputRepairEvidenceFromArtifact(
    JsonCodec.parseObjectOrNull(json)?.let(JsonCodec::jsonElementToValue),
  )

internal fun FeatureTaskRuntimeProjectionMeasurement.encodeTelemetry(): Any = asTelemetryPayload()

internal fun FeatureTaskRuntimeSharedEvidenceMeasurement.encodeTelemetry(): Any = asTelemetryPayload()

internal fun FeatureTaskRuntimeRejectionMeasurement.encodeTelemetry(): Any = asTelemetryPayload()

internal fun FeatureTaskRuntimeDiagnosticDegradationMeasurement.encodeTelemetry(): Any = asTelemetryPayload()
