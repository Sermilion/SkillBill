package skillbill.workflow.taskruntime.artifact

import skillbill.contracts.JsonCodec
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeDiagnosticSignal
import skillbill.workflow.taskruntime.model.audit.featureTaskRuntimeDiagnosticSignalsFromWire
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactValidation
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeDiagnosticDegradationMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProjectionMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRejectionMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.persistence.task.runtime.run.toArtifactMap
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition

fun FeatureTaskRuntimeFindingVerificationDisposition.asWorkflowArtifactEntry(): Any = toArtifactMap()

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

fun FeatureTaskRuntimeDiagnosticSignal.asWorkflowArtifactEntry(): Any = toArtifactMap()

fun decodeDiagnosticSignalsFromArtifact(raw: Any?): List<FeatureTaskRuntimeDiagnosticSignal> =
  featureTaskRuntimeDiagnosticSignalsFromWire(raw)

fun FeatureTaskRuntimePhaseOutputRepairEvidence.asWorkflowArtifactEntry(): Any = toArtifactMap()

fun decodePhaseOutputRepairEvidenceFromArtifact(raw: Any?): FeatureTaskRuntimePhaseOutputRepairEvidence? =
  JsonCodec.anyToStringAnyMap(raw)?.let(FeatureTaskRuntimePhaseOutputRepairEvidence::fromArtifactMap)

internal fun decodePhaseOutputRepairEvidenceFromArtifact(
  raw: Map<String, Any?>,
): FeatureTaskRuntimePhaseOutputRepairEvidence = FeatureTaskRuntimePhaseOutputRepairEvidence.fromArtifactMap(raw)

fun FeatureTaskRuntimeVerificationBoundaryHeadingProvenance.asWorkflowArtifactEntry(): Any = toArtifactMap()

internal fun decodeVerificationBoundaryHeadingProvenanceFromArtifact(
  raw: Map<String, Any?>,
  path: String,
): FeatureTaskRuntimeVerificationBoundaryHeadingProvenance =
  FeatureTaskRuntimeVerificationBoundaryHeadingProvenance.fromArtifactMap(raw, path)

fun PhaseHandoffProjectionDeclaration.asWorkflowArtifactEntry(): Any = toArtifactMap()

internal fun decodePhaseHandoffProjectionDeclarationFromArtifact(
  raw: Map<String, Any?>,
  foundationValidator: FeatureTaskRuntimeWireArtifactValidation,
): PhaseHandoffProjectionDeclaration = PhaseHandoffProjectionDeclaration.fromArtifactMap(raw, foundationValidator)

fun NormalizedFeatureTaskRuntimePhaseOutput.envelopeWireMap(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(envelopePayload())

fun FeatureTaskRuntimeProjectionMeasurement.asTelemetryPayload(): Any = toTelemetryMap()

fun FeatureTaskRuntimeSharedEvidenceMeasurement.asTelemetryPayload(): Any = toTelemetryMap()

fun FeatureTaskRuntimeRejectionMeasurement.asTelemetryPayload(): Any = toTelemetryMap()

fun FeatureTaskRuntimeDiagnosticDegradationMeasurement.asTelemetryPayload(): Any = toTelemetryMap()
