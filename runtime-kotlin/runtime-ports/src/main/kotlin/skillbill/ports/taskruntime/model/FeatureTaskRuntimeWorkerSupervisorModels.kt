package skillbill.ports.taskruntime.model

data class FeatureTaskRuntimeProcessIdentity(
  val hostIdentity: String,
  val bootIdentity: String,
  val pid: Long,
  val processBirthToken: String,
)

sealed interface FeatureTaskRuntimeProcessInspection {
  data object ExactLive : FeatureTaskRuntimeProcessInspection

  data object NotRunning : FeatureTaskRuntimeProcessInspection

  data object ForeignHost : FeatureTaskRuntimeProcessInspection

  data object PidReused : FeatureTaskRuntimeProcessInspection

  data class Unsupported(val reason: String) : FeatureTaskRuntimeProcessInspection
}

fun FeatureTaskRuntimeProcessInspection.isConfirmedDead(): Boolean =
  when (this) {
    FeatureTaskRuntimeProcessInspection.NotRunning -> true
    FeatureTaskRuntimeProcessInspection.ExactLive -> false
    FeatureTaskRuntimeProcessInspection.ForeignHost -> false
    FeatureTaskRuntimeProcessInspection.PidReused -> false
    is FeatureTaskRuntimeProcessInspection.Unsupported -> false
  }

sealed interface FeatureTaskRuntimeHeartbeatTick {
  data object Renewed : FeatureTaskRuntimeHeartbeatTick

  data object WorkflowFinished : FeatureTaskRuntimeHeartbeatTick

  data class FencingLost(val reason: String) : FeatureTaskRuntimeHeartbeatTick
}

data class FeatureTaskRuntimeHeartbeatPlan(
  val label: String,
  val intervalSeconds: Long,
  val leaseSeconds: Long,
  val retryDelaySeconds: Long = 1,
)
