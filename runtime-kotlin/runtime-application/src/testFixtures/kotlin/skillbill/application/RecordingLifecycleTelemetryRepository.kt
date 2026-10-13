package skillbill.application

import skillbill.ports.telemetry.lifecycle.FeatureTaskRuntimeLifecycleTelemetryRepository
import skillbill.ports.telemetry.lifecycle.FeatureTaskRuntimeTelemetryMeasurementRepository
import skillbill.ports.telemetry.lifecycle.FeatureVerifyLifecycleTelemetryRepository
import skillbill.ports.telemetry.lifecycle.GoalLifecycleTelemetryRepository
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.lifecycle.PrDescriptionLifecycleTelemetryRepository
import skillbill.ports.telemetry.lifecycle.QualityCheckLifecycleTelemetryRepository
import skillbill.ports.telemetry.lifecycle.ReviewStageTelemetryMeasurementRepository
import skillbill.review.model.ReviewStageDegradationMeasurement
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.FeatureVerifyStartedRecord
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import skillbill.telemetry.model.PrDescriptionGeneratedRecord
import skillbill.telemetry.model.QualityCheckFinishedRecord
import skillbill.telemetry.model.QualityCheckStartedRecord
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeDiagnosticDegradationMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProjectionMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRejectionMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement
import java.time.Instant

private object QualityCheckLifecycleTelemetryNoop : QualityCheckLifecycleTelemetryRepository {
  override fun qualityCheckStarted(
    record: QualityCheckStartedRecord,
    level: String,
  ) = Unit

  override fun qualityCheckFinished(
    record: QualityCheckFinishedRecord,
    level: String,
  ) = Unit
}

private object FeatureVerifyLifecycleTelemetryNoop : FeatureVerifyLifecycleTelemetryRepository {
  override fun featureVerifyStarted(
    record: FeatureVerifyStartedRecord,
    level: String,
  ) = Unit

  override fun featureVerifyFinished(
    record: FeatureVerifyFinishedRecord,
    level: String,
  ) = Unit
}

private object PrDescriptionLifecycleTelemetryNoop : PrDescriptionLifecycleTelemetryRepository {
  override fun prDescriptionGenerated(
    record: PrDescriptionGeneratedRecord,
    level: String,
  ) = Unit
}

private object GoalLifecycleTelemetryNoop : GoalLifecycleTelemetryRepository {
  override fun goalStarted(
    record: GoalStartedRecord,
    level: String,
  ) = Unit

  override fun goalSubtaskFinished(
    record: GoalSubtaskFinishedRecord,
    level: String,
  ) = Unit

  override fun goalFinished(
    record: GoalFinishedRecord,
    level: String,
  ) = Unit

  override fun cancelGoalIssue(
    parentWorkflowId: String,
    issueKey: String,
    canceledAt: Instant,
  ): Boolean = false

  override fun restartCanceledGoalIssue(
    parentWorkflowId: String,
    issueKey: String,
    restartedAt: Instant,
  ): Boolean = false

  override fun goalIssueFinished(
    record: GoalIssueFinishedRecord,
    level: String,
  ) = Unit
}

class RecordingLifecycleTelemetryRepository(
  private val throwOnDiagnosticDegradation: Boolean = false,
) : LifecycleTelemetryRepository,
  FeatureTaskRuntimeTelemetryMeasurementRepository,
  FeatureTaskRuntimeLifecycleTelemetryRepository,
  ReviewStageTelemetryMeasurementRepository,
  QualityCheckLifecycleTelemetryRepository by QualityCheckLifecycleTelemetryNoop,
  FeatureVerifyLifecycleTelemetryRepository by FeatureVerifyLifecycleTelemetryNoop,
  PrDescriptionLifecycleTelemetryRepository by PrDescriptionLifecycleTelemetryNoop,
  GoalLifecycleTelemetryRepository by GoalLifecycleTelemetryNoop {
  val startedRecords = mutableListOf<FeatureTaskRuntimeStartedRecord>()
  val finishedRecords = mutableListOf<FeatureTaskRuntimeFinishedRecord>()
  val sharedEvidenceMeasurements =
    mutableListOf<FeatureTaskRuntimeSharedEvidenceMeasurement>()
  val diagnosticDegradationMeasurements =
    mutableListOf<FeatureTaskRuntimeDiagnosticDegradationMeasurement>()

  override fun featureTaskRuntimeSharedEvidence(record: FeatureTaskRuntimeSharedEvidenceMeasurement) {
    sharedEvidenceMeasurements += record
  }

  override fun featureTaskRuntimeProjectionMeasurement(record: FeatureTaskRuntimeProjectionMeasurement) = Unit

  override fun featureTaskRuntimeRejection(record: FeatureTaskRuntimeRejectionMeasurement) = Unit

  override fun reviewStageDegradation(record: ReviewStageDegradationMeasurement) = Unit

  override fun featureTaskRuntimeDiagnosticDegradation(record: FeatureTaskRuntimeDiagnosticDegradationMeasurement) {
    if (throwOnDiagnosticDegradation) error("telemetry sink failed")
    diagnosticDegradationMeasurements += record
  }

  override fun featureTaskRuntimeStarted(
    record: FeatureTaskRuntimeStartedRecord,
    level: String,
  ) {
    startedRecords += record
  }

  override fun featureTaskRuntimeFinished(
    record: FeatureTaskRuntimeFinishedRecord,
    level: String,
  ) {
    finishedRecords += record
  }
}
