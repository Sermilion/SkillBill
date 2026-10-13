package skillbill.engine.work.control

import skillbill.engine.goalrunner.model.GoalRunnerStopStatus
import skillbill.engine.work.control.model.WorkControlRejection
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection

internal fun goalRunnerStopWorkControlRejection(status: GoalRunnerStopStatus): WorkControlRejection? =
  when (status) {
    GoalRunnerStopStatus.FOREIGN_HOST -> WorkControlRejection.FOREIGN_HOST
    GoalRunnerStopStatus.PID_REUSED -> WorkControlRejection.PID_REUSED
    GoalRunnerStopStatus.UNINSPECTABLE -> WorkControlRejection.UNINSPECTABLE
    GoalRunnerStopStatus.IDENTITY_MISMATCH -> WorkControlRejection.IDENTITY_MISMATCH
    else -> null
  }

internal fun processInspectionWorkControlRejection(
  inspection: FeatureTaskRuntimeProcessInspection,
): WorkControlRejection? =
  when (inspection) {
    FeatureTaskRuntimeProcessInspection.NotRunning -> null
    FeatureTaskRuntimeProcessInspection.ExactLive -> WorkControlRejection.TERMINATION_INCOMPLETE
    FeatureTaskRuntimeProcessInspection.ForeignHost -> WorkControlRejection.FOREIGN_HOST
    FeatureTaskRuntimeProcessInspection.PidReused -> WorkControlRejection.PID_REUSED
    is FeatureTaskRuntimeProcessInspection.Unsupported -> WorkControlRejection.UNINSPECTABLE
  }

internal fun processInspectionCancelRejection(
  inspection: FeatureTaskRuntimeProcessInspection,
): WorkControlRejection? =
  when (inspection) {
    FeatureTaskRuntimeProcessInspection.NotRunning -> null
    FeatureTaskRuntimeProcessInspection.ExactLive -> null
    FeatureTaskRuntimeProcessInspection.ForeignHost -> WorkControlRejection.FOREIGN_HOST
    FeatureTaskRuntimeProcessInspection.PidReused -> WorkControlRejection.PID_REUSED
    is FeatureTaskRuntimeProcessInspection.Unsupported -> WorkControlRejection.UNINSPECTABLE
  }
