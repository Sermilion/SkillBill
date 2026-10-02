package skillbill.engine.featuretask.lifecycle.core

import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRecording
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProducerOutputRead
import skillbill.engine.featuretask.model.phase.ProducerOutputQueryArgs
import skillbill.engine.featuretask.model.review.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.engine.featuretask.model.review.RejectedOutputDiagnosticDegradeRequest
import skillbill.engine.featuretask.model.review.RejectedOutputDiagnosticPersistRequest
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.error.core.RejectedOutputDiagnosticFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rejectedOutputDiagnosticConflictMessage
import skillbill.error.core.rejectedOutputDiagnosticInvalidRequestMessage
import skillbill.error.shellcontent.InvalidProducerOutputEvidenceSchemaError
import skillbill.error.shellcontent.InvalidRejectedOutputDiagnosticSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.ProducerOutputEvidenceValidator
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.diagnostics.model.evidenceKey
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodeDiagnosticSignalsFromArtifact
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeDiagnosticSignal
import skillbill.workflow.taskruntime.model.audit.featureTaskRuntimeAppendDiagnosticSignal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDiagnosticFailureClass
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeDiagnosticDegradationMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRejectionMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.featureTaskRuntimeRejectionCapOf
import skillbill.workflow.taskruntime.model.handoff.task.featureTaskRuntimeRejectionViolationClassOf
import java.time.Clock

private fun SkillBillRuntimeException.degradableFailureClass(): FeatureTaskRuntimeDiagnosticFailureClass? =
  when (code as? RejectedOutputDiagnosticFailureCode) {
    RejectedOutputDiagnosticFailureCode.CONFLICT -> FeatureTaskRuntimeDiagnosticFailureClass.CONFLICT
    RejectedOutputDiagnosticFailureCode.PERMISSION -> FeatureTaskRuntimeDiagnosticFailureClass.PERMISSION
    RejectedOutputDiagnosticFailureCode.CORRUPT -> FeatureTaskRuntimeDiagnosticFailureClass.CORRUPT
    RejectedOutputDiagnosticFailureCode.PERSISTENCE,
    RejectedOutputDiagnosticFailureCode.RETRIEVAL,
    -> FeatureTaskRuntimeDiagnosticFailureClass.PERSISTENCE
    RejectedOutputDiagnosticFailureCode.INVALID_REQUEST,
    RejectedOutputDiagnosticFailureCode.INVALID_CONFIGURATION,
    null,
    -> null
  }

private fun conflictFailure(identity: String): SkillBillRuntimeException =
  SkillBillRuntimeException(
    RejectedOutputDiagnosticFailureCode.CONFLICT,
    rejectedOutputDiagnosticConflictMessage(identity),
  )

private fun invalidRequestFailure(reason: String): SkillBillRuntimeException =
  SkillBillRuntimeException(
    RejectedOutputDiagnosticFailureCode.INVALID_REQUEST,
    rejectedOutputDiagnosticInvalidRequestMessage(reason),
  )

internal class FeatureTaskRuntimeRejectedOutputRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  private val rejectedOutputDiagnosticMetadataValidator: RejectedOutputDiagnosticMetadataValidator,
  private val producerOutputEvidenceValidator: ProducerOutputEvidenceValidator,
  private val clock: Clock,
) {
  private sealed class DiagnosticWriteOutcome<out T> {
    class Written<T>(val value: T) : DiagnosticWriteOutcome<T>()

    class Degraded(
      val failureClass: FeatureTaskRuntimeDiagnosticFailureClass,
    ) : DiagnosticWriteOutcome<Nothing>()
  }

  fun recordRejectedOutput(
    request: RejectedOutputDiagnosticRequest,
    producerGeneration: Int,
  ): FeatureTaskRuntimeRejectedOutputWrite {
    val evidence =
      ProducerOutputEvidence(
        workflowId = request.workflowId,
        phaseId = request.phaseId,
        attempt = request.attempt,
        agentId = request.agentId,
        model = request.model,
        recordedAt = clock.instant(),
        byteSize = request.observedByteSize,
        sha256 = request.observedSha256,
        payload = request.rawResponse.takeUnless { request.truncated },
        generation = producerGeneration,
        repairTurn = request.repairTurn,
      )
    return when (
      val outcome =
        degradeDiagnosticFailure(
          RejectedOutputDiagnosticDegradeRequest(
            workflowId = request.workflowId,
            operation = "record-rejected-output",
            conflictingKey = evidence.evidenceKey(),
            phaseId = request.phaseId,
            attempt = request.attempt,
            repairTurn = request.repairTurn,
            generation = producerGeneration,
          ),
        ) {
          database.transaction { unitOfWork ->
            val service = diagnosticService(unitOfWork)
            service.retainProducerOutput(evidence)
            when (val recording = service.record(request)) {
              is RejectedOutputDiagnosticRecording.Recorded -> Unit
              is RejectedOutputDiagnosticRecording.Conflict -> throw conflictFailure(recording.identity)
              is RejectedOutputDiagnosticRecording.InvalidRequest -> throw invalidRequestFailure(recording.reason)
            }
            recordRejectionMeasurement(unitOfWork, request)
          }
        }
    ) {
      is DiagnosticWriteOutcome.Written<*> ->
        FeatureTaskRuntimeRejectedOutputWrite.Written(
          RejectedOutputDiagnosticService.stableIdentity(
            request.workflowId,
            request.phaseId,
            request.attempt,
            request.repairTurn,
          ),
        )
      is DiagnosticWriteOutcome.Degraded -> FeatureTaskRuntimeRejectedOutputWrite.Degraded(outcome.failureClass)
    }
  }

  private fun recordRejectionMeasurement(
    unitOfWork: UnitOfWork,
    request: RejectedOutputDiagnosticRequest,
  ) {
    runCatching {
      unitOfWork.lifecycleTelemetry.featureTaskRuntimeRejection(
        FeatureTaskRuntimeRejectionMeasurement(
          workflowId = request.workflowId,
          phaseId = request.phaseId,
          iteration = request.attempt.coerceAtLeast(1),
          rule = request.rule,
          pointerPath = request.path.ifBlank { "/" },
          violationClass = featureTaskRuntimeRejectionViolationClassOf(request.reason),
          declaredCap = featureTaskRuntimeRejectionCapOf(request.reason),
          exhaustedFixLoop = request.exhaustedFixLoop,
        ),
      )
    }
  }

  fun retainProducerOutput(evidence: ProducerOutputEvidence) {
    degradeDiagnosticFailure(
      RejectedOutputDiagnosticDegradeRequest(
        workflowId = evidence.workflowId,
        operation = "retain-producer-output",
        conflictingKey = evidence.evidenceKey(),
        phaseId = evidence.phaseId,
        attempt = evidence.attempt,
        repairTurn = evidence.repairTurn,
        generation = evidence.generation,
      ),
    ) {
      database.transaction { unitOfWork ->
        diagnosticService(unitOfWork).retainProducerOutput(evidence)
      }
    }
  }

  fun producerOutput(args: ProducerOutputQueryArgs): FeatureTaskRuntimeProducerOutputRead {
    val workflowId = args.workflowId
    val phaseId = args.phaseId
    val attempt = args.attempt
    val agentId = args.agentId
    val generation = args.generation
    val conflictingKey = "$workflowId:$phaseId:$generation:$attempt:*:$agentId"

    fun unreadable(failureClass: FeatureTaskRuntimeDiagnosticFailureClass): FeatureTaskRuntimeProducerOutputRead {
      persistDegradedDiagnostic(
        RejectedOutputDiagnosticPersistRequest(
          workflowId = workflowId,
          operation = "read-producer-output",
          conflictingKey = conflictingKey,
          phaseId = phaseId,
          attempt = attempt,
          repairTurn = null,
          generation = generation,
          failureClass = failureClass,
        ),
      )
      return FeatureTaskRuntimeProducerOutputRead.Unreadable(failureClass)
    }
    return try {
      val evidence =
        database.read { unitOfWork ->
          unitOfWork.rejectedOutputDiagnostics.readProducerOutput(workflowId, phaseId, attempt, agentId, generation)
        }
      if (evidence == null) {
        FeatureTaskRuntimeProducerOutputRead.Absent
      } else {
        FeatureTaskRuntimeProducerOutputRead.Found(evidence)
      }
    } catch (_: InvalidProducerOutputEvidenceSchemaError) {
      unreadable(FeatureTaskRuntimeDiagnosticFailureClass.SCHEMA)
    } catch (_: InvalidRejectedOutputDiagnosticSchemaError) {
      unreadable(FeatureTaskRuntimeDiagnosticFailureClass.SCHEMA)
    } catch (error: SkillBillRuntimeException) {
      unreadable(error.degradableFailureClass() ?: throw error)
    }
  }

  private fun <T> degradeDiagnosticFailure(
    request: RejectedOutputDiagnosticDegradeRequest,
    block: () -> T,
  ): DiagnosticWriteOutcome<T> {
    fun degrade(failureClass: FeatureTaskRuntimeDiagnosticFailureClass): DiagnosticWriteOutcome<T> {
      persistDegradedDiagnostic(
        RejectedOutputDiagnosticPersistRequest(
          workflowId = request.workflowId,
          operation = request.operation,
          conflictingKey = request.conflictingKey,
          phaseId = request.phaseId,
          attempt = request.attempt,
          repairTurn = request.repairTurn,
          generation = request.generation,
          failureClass = failureClass,
        ),
      )
      return DiagnosticWriteOutcome.Degraded(failureClass)
    }
    return try {
      DiagnosticWriteOutcome.Written(block())
    } catch (_: InvalidProducerOutputEvidenceSchemaError) {
      degrade(FeatureTaskRuntimeDiagnosticFailureClass.SCHEMA)
    } catch (_: InvalidRejectedOutputDiagnosticSchemaError) {
      degrade(FeatureTaskRuntimeDiagnosticFailureClass.SCHEMA)
    } catch (error: SkillBillRuntimeException) {
      degrade(error.degradableFailureClass() ?: throw error)
    }
  }

  private fun persistDegradedDiagnostic(request: RejectedOutputDiagnosticPersistRequest) {
    val signal =
      FeatureTaskRuntimeDiagnosticSignal(
        operation = request.operation,
        failureClass = request.failureClass,
        conflictingKey = request.conflictingKey,
        phaseId = request.phaseId,
        attempt = request.attempt.coerceAtLeast(0),
        repairTurn = request.repairTurn?.coerceAtLeast(0),
        generation = request.generation.coerceAtLeast(0),
        recordedAt = clock.instant().toString(),
      )
    persistDiagnosticSignal(request.workflowId, signal)
    recordDegradationMeasurement(request.workflowId, signal)
  }

  private fun recordDegradationMeasurement(
    workflowId: String,
    signal: FeatureTaskRuntimeDiagnosticSignal,
  ) {
    runCatching {
      database.transaction { unitOfWork ->
        unitOfWork.lifecycleTelemetry.featureTaskRuntimeDiagnosticDegradation(
          FeatureTaskRuntimeDiagnosticDegradationMeasurement(
            workflowId = workflowId,
            phaseId = signal.phaseId,
            attempt = signal.attempt,
            repairTurn = signal.repairTurn,
            generation = signal.generation,
            operation = signal.operation,
            failureClass = signal.failureClass,
            conflictingKey = signal.conflictingKey,
          ),
        )
      }
    }
  }

  private fun persistDiagnosticSignal(
    workflowId: String,
    signal: FeatureTaskRuntimeDiagnosticSignal,
  ) {
    runCatching {
      database.transaction { unitOfWork ->
        val record =
          unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
            ?: return@transaction
        val existing =
          decodeDiagnosticSignalsFromArtifact(
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS.value(record.artifacts),
          )
        workflowPersistence.persistArtifactsPatch(
          unitOfWork.workflowStates,
          record,
          mapOf(
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS.entry(
              featureTaskRuntimeAppendDiagnosticSignal(existing, signal).map { it.asWorkflowArtifactEntry() },
            ),
          ),
        )
      }
    }
  }

  fun loadDiagnosticSignals(workflowId: String): List<FeatureTaskRuntimeDiagnosticSignal> =
    database.read { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@read emptyList()
      decodeDiagnosticSignalsFromArtifact(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS.value(record.artifacts),
      )
    }

  private fun diagnosticService(unitOfWork: UnitOfWork): RejectedOutputDiagnosticService =
    RejectedOutputDiagnosticService(
      repository = unitOfWork.rejectedOutputDiagnostics,
      permissions = unitOfWork.rejectedOutputDiagnosticPermissions,
      metadataValidator = rejectedOutputDiagnosticMetadataValidator,
      producerEvidenceValidator = producerOutputEvidenceValidator,
      clock = clock,
    )
}
