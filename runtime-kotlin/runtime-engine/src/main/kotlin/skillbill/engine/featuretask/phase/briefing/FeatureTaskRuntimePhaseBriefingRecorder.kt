package skillbill.engine.featuretask.phase.briefing

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProjectionRejection
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.deliveredProjectionHistoryFrom
import skillbill.engine.featuretask.persist.deliveredProjectionsFrom
import skillbill.engine.featuretask.persist.phaseBriefingsFrom
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.phase.core.toMeasurementFailureClassification
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteKind
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteRejected
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.taskruntime.validateDeclaration
import skillbill.ports.taskruntime.validateEnvelope
import skillbill.ports.taskruntime.validateMeasurement
import skillbill.ports.taskruntime.validatePersistenceRecord
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.asTelemetryPayload
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProjectionMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeDeliveredProjectionRecord

class FeatureTaskRuntimePhaseBriefingRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  val wireArtifactValidator: FeatureTaskRuntimeWireArtifactValidator,
) {
  fun recordPhaseBriefing(
    workflowId: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement?,
    attempt: Int,
  ) =
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: throw RequiredPhaseWriteRejected(
            writeKind = RequiredPhaseWriteKind.BRIEFING,
            workflowId = workflowId,
            phaseId = briefing.phaseId,
            attempt = attempt,
          )
      wireArtifactValidator.validateEnvelope(briefing.handoffEnvelope.asWorkflowArtifactEntry(), workflowId)
      val artifacts = record.artifacts
      val updatedBriefings =
        LinkedHashMap(phaseBriefingsFrom(artifacts, wireArtifactValidator::validateEnvelopeWire))
          .apply { put(briefing.phaseId, briefing) }
      val deliveredHistory =
        deliveredProjectionHistoryFrom(
          artifacts,
          wireArtifactValidator::validateEnvelopeWire,
          wireArtifactValidator::validatePersistenceWire,
        )
      val delivered = nextDeliveredProjectionRecord(workflowId, briefing, deliveredHistory)
      wireArtifactValidator.validatePersistenceRecord(
        delivered.asWorkflowArtifactEntry(),
        "delivered-projection:${briefing.phaseId}",
      )
      recordProjectionMeasurements(unitOfWork, workflowId, briefing, delivered, artifacts)
      recordSharedEvidenceMeasurement(unitOfWork, sharedEvidenceMeasurement)
      val updatedDelivered =
        LinkedHashMap(deliveredHistory)
          .apply {
            entries.removeIf { (_, value) -> value.consumerPhaseId == briefing.phaseId }
            put(deliveredProjectionKey(delivered), delivered)
          }
      val patch =
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS.entry(
            updatedBriefings.mapValues { (_, value) -> value.asBriefingArtifactEntry() },
          ),
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS.entry(
            updatedDelivered.mapValues { (_, value) -> value.asWorkflowArtifactEntry() },
          ),
        )
      workflowPersistence.persistArtifactsPatch(unitOfWork.workflowStates, record, patch)
    }

  fun recordProjectionRejection(
    workflowId: String,
    consumerPhaseId: String,
    error: InvalidFeatureTaskRuntimeHandoffProjectionError,
    repositoryCheckpointFingerprint: String?,
  ): Boolean =
    database.transaction { unitOfWork ->
      recordProjectionRejectionMeasurement(
        unitOfWork,
        FeatureTaskRuntimeProjectionRejection(
          workflowId = workflowId,
          consumerPhaseId = consumerPhaseId,
          projectionContractId = error.projectionContractId.ifBlank { "unknown" },
          producerIteration = FeatureTaskRuntimeProducerIteration(consumerPhaseId, 1),
          repositoryCheckpointFingerprint = repositoryCheckpointFingerprint,
          failureClassification = error.failureKind.toMeasurementFailureClassification(),
          sourceLabel = error.projectionName,
        ),
      )
    }

  fun recordProjectionRejection(rejection: FeatureTaskRuntimeProjectionRejection): Boolean =
    database.transaction { unitOfWork ->
      recordProjectionRejectionMeasurement(unitOfWork, rejection)
    }

  fun validateHandoffDeclarations(declarations: List<PhaseHandoffProjectionDeclaration>) {
    declarations.forEach { declaration ->
      wireArtifactValidator.validateDeclaration(
        declaration.asWorkflowArtifactEntry(),
        "phase-handoff-declaration:${declaration.consumerPhaseId}:${declaration.projectionName}",
      )
    }
  }

  fun loadPhaseBriefings(workflowId: String): Map<String, FeatureTaskRuntimePhaseLaunchBriefing>? =
    database.read { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@read null
      phaseBriefingsFrom(record.artifacts) { envelope ->
        wireArtifactValidator.validateEnvelope(envelope, workflowId)
      }
    }

  fun loadDeliveredProjections(workflowId: String): Map<String, FeatureTaskRuntimeDeliveredProjectionRecord>? =
    database.read { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@read null
      deliveredProjectionsFrom(
        record.artifacts,
        validateEnvelope = { envelope -> wireArtifactValidator.validateEnvelope(envelope, workflowId) },
        validatePersistenceRecord = { persistence ->
          wireArtifactValidator.validatePersistenceRecord(persistence, "delivered-projection:$workflowId")
        },
      )
    }
}

fun FeatureTaskRuntimePhaseBriefingRecorder.nextDeliveredProjectionRecord(
  workflowId: String,
  briefing: FeatureTaskRuntimePhaseLaunchBriefing,
  deliveredHistory: Map<String, FeatureTaskRuntimeDeliveredProjectionRecord>,
): FeatureTaskRuntimeDeliveredProjectionRecord {
  val existingDelivered =
    deliveredHistory.values
      .filter { it.consumerPhaseId == briefing.phaseId }
      .maxByOrNull(FeatureTaskRuntimeDeliveredProjectionRecord::iteration)
  return FeatureTaskRuntimeDeliveredProjectionRecord(
    workflowId = workflowId,
    consumerPhaseId = briefing.phaseId,
    iteration = (existingDelivered?.iteration ?: 0) + 1,
    envelope = briefing.handoffEnvelope,
  )
}

fun FeatureTaskRuntimePhaseBriefingRecorder.recordProjectionMeasurements(
  unitOfWork: UnitOfWork,
  workflowId: String,
  briefing: FeatureTaskRuntimePhaseLaunchBriefing,
  delivered: FeatureTaskRuntimeDeliveredProjectionRecord,
  artifacts: Map<String, Any?>,
) {
  val privatePhaseRecords = decodePhaseRecords(artifacts)
  briefing.handoffEnvelope.projections.forEach { projection ->
    val deliveredProjectionUtf8Bytes = projection.utf8ByteSize
    val privateEvidenceUtf8Bytes =
      privatePhaseRecords[projection.producerIteration.phaseId]
        ?.outputArtifact
        ?.toByteArray(Charsets.UTF_8)
        ?.size
        ?: 0
    val measurement =
      FeatureTaskRuntimeProjectionMeasurement(
        workflowId = workflowId,
        consumerPhaseId = briefing.phaseId,
        projectionContractId = projection.projectionContractId,
        producerIteration = projection.producerIteration,
        repositoryCheckpointFingerprint = delivered.repositoryCheckpointFingerprint,
        projectedUtf8Bytes = projection.utf8ByteSize,
        projectedCollectionItems = projection.itemCount,
        estimatedTokens = (projection.utf8ByteSize + 3) / 4,
        privateEvidenceUtf8Bytes = privateEvidenceUtf8Bytes,
        deliveredProjectionUtf8Bytes = deliveredProjectionUtf8Bytes,
      )
    wireArtifactValidator.validateMeasurement(
      measurement.asTelemetryPayload(),
      "projection-delivery:${briefing.phaseId}:${projection.projectionName}",
    )
    unitOfWork.lifecycleTelemetry.featureTaskRuntimeProjectionMeasurement(measurement)
  }
}

fun FeatureTaskRuntimePhaseBriefingRecorder.recordSharedEvidenceMeasurement(
  unitOfWork: UnitOfWork,
  measurement: FeatureTaskRuntimeSharedEvidenceMeasurement?,
) {
  if (measurement == null) return
  runCatching {
    unitOfWork.lifecycleTelemetry.featureTaskRuntimeSharedEvidence(measurement)
  }
}

fun deliveredProjectionKey(delivered: FeatureTaskRuntimeDeliveredProjectionRecord): String =
  listOf(
    delivered.workflowId,
    delivered.consumerPhaseId,
    delivered.iteration.toString(),
    delivered.sourceProducerIterations
      .sortedWith(
        compareBy(
          FeatureTaskRuntimeProducerIteration::phaseId,
          FeatureTaskRuntimeProducerIteration::iteration,
        ),
      )
      .joinToString(separator = ",") { "${it.phaseId}#${it.iteration}" },
    delivered.repositoryCheckpointFingerprint,
  ).joinToString(separator = "|")

internal fun FeatureTaskRuntimePhaseBriefingRecorder.recordProjectionRejectionMeasurement(
  unitOfWork: UnitOfWork,
  rejection: FeatureTaskRuntimeProjectionRejection,
): Boolean {
  if (unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, rejection.workflowId) == null) {
    return false
  }
  val measurement =
    FeatureTaskRuntimeProjectionMeasurement(
      workflowId = rejection.workflowId,
      consumerPhaseId = rejection.consumerPhaseId,
      projectionContractId = rejection.projectionContractId.ifBlank { "unknown" },
      producerIteration = rejection.producerIteration,
      repositoryCheckpointFingerprint =
        rejection.repositoryCheckpointFingerprint
          ?: "not_resolved:${rejection.consumerPhaseId}",
      projectedUtf8Bytes = 0,
      projectedCollectionItems = 0,
      estimatedTokens = 0,
      privateEvidenceUtf8Bytes = 0,
      deliveredProjectionUtf8Bytes = 0,
      failureClassification = rejection.failureClassification,
    )
  wireArtifactValidator.validateMeasurement(
    measurement.asTelemetryPayload(),
    "projection-rejection:${rejection.consumerPhaseId}:${rejection.sourceLabel}",
  )
  unitOfWork.lifecycleTelemetry.featureTaskRuntimeProjectionMeasurement(measurement)
  return true
}

internal fun FeatureTaskRuntimeWireArtifactValidator.validateEnvelopeWire(envelope: Map<String, Any?>) =
  validateEnvelope(envelope, workflowId = null)

internal fun FeatureTaskRuntimeWireArtifactValidator.validatePersistenceWire(record: Map<String, Any?>) =
  validatePersistenceRecord(record, "delivered-projection")
