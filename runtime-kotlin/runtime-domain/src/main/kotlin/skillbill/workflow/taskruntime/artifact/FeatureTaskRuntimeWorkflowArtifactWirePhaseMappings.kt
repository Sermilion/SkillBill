package skillbill.workflow.taskruntime.artifact

import skillbill.contracts.JsonCodec
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeDiagnosticSignal
import skillbill.workflow.taskruntime.model.audit.featureTaskRuntimeDiagnosticSignalsFromWire
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeDiagnosticDegradationMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProjectionMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRejectionMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.persistence.toArtifactMap
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition

fun FeatureTaskRuntimeFindingVerificationDisposition.asWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

fun decodeFindingVerificationDispositionFromArtifact(
  raw: Any?,
  path: String = "finding_verification",
): FeatureTaskRuntimeFindingVerificationDisposition? =
  JsonCodec.anyToStringAnyMap(raw)?.let { FeatureTaskRuntimeFindingVerificationDisposition.fromArtifactMap(it, path) }

internal fun decodeFindingVerificationDispositionFromArtifact(
  raw: Map<String, Any?>,
  path: String = "finding_verification",
): FeatureTaskRuntimeFindingVerificationDisposition =
  FeatureTaskRuntimeFindingVerificationDisposition.fromArtifactMap(raw, path)

fun FeatureTaskRuntimeDiagnosticSignal.asWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

fun decodeDiagnosticSignalsFromArtifact(raw: Any?): List<FeatureTaskRuntimeDiagnosticSignal> =
  featureTaskRuntimeDiagnosticSignalsFromWire(raw)

fun FeatureTaskRuntimePhaseOutputRepairEvidence.asWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

fun decodePhaseOutputRepairEvidenceFromArtifact(raw: Any?): FeatureTaskRuntimePhaseOutputRepairEvidence? =
  JsonCodec.anyToStringAnyMap(raw)?.let(FeatureTaskRuntimePhaseOutputRepairEvidence::fromArtifactMap)

internal fun decodePhaseOutputRepairEvidenceFromArtifact(
  raw: Map<String, Any?>,
): FeatureTaskRuntimePhaseOutputRepairEvidence = FeatureTaskRuntimePhaseOutputRepairEvidence.fromArtifactMap(raw)

fun FeatureTaskRuntimeVerificationBoundaryHeadingProvenance.asWorkflowArtifactEntry():
  FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

internal fun decodeVerificationBoundaryHeadingProvenanceFromArtifact(
  raw: Map<String, Any?>,
  path: String,
): FeatureTaskRuntimeVerificationBoundaryHeadingProvenance =
  FeatureTaskRuntimeVerificationBoundaryHeadingProvenance.fromArtifactMap(raw, path)

fun PhaseHandoffProjectionDeclaration.asWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

fun NormalizedFeatureTaskRuntimePhaseOutput.envelopeWireMap(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(envelopePayload())

fun FeatureTaskRuntimeProjectionMeasurement.asTelemetryPayload(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toTelemetryMap())

fun FeatureTaskRuntimeSharedEvidenceMeasurement.asTelemetryPayload(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toTelemetryMap())

fun FeatureTaskRuntimeRejectionMeasurement.asTelemetryPayload(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toTelemetryMap())

fun FeatureTaskRuntimeDiagnosticDegradationMeasurement.asTelemetryPayload(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toTelemetryMap())
