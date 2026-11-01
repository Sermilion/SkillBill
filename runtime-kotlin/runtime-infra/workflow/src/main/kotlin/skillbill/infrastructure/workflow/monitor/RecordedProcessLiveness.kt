package skillbill.infrastructure.workflow.monitor

import skillbill.error.core.catchingNonCancellation
import skillbill.ports.monitor.MonitorProcessInspector
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.taskruntime.model.isConfirmedDead

internal enum class RecordedProcessLiveness { LIVE, DEAD, UNREADABLE, UNCONFIRMED }

internal fun MonitorProcessInspector.livenessOf(recorded: FeatureTaskRuntimeProcessIdentity): RecordedProcessLiveness {
  val local = catchingNonCancellation { currentProcess() }.getOrNull()
  val inspection = if (local != null && local.hostIdentity == recorded.hostIdentity) inspect(recorded) else null
  return when {
    inspection == null -> RecordedProcessLiveness.UNREADABLE
    inspection == FeatureTaskRuntimeProcessInspection.ExactLive -> RecordedProcessLiveness.LIVE
    !inspection.isConfirmedDead() -> RecordedProcessLiveness.UNCONFIRMED
    else -> RecordedProcessLiveness.DEAD
  }
}
