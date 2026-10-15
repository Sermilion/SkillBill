package skillbill.ports.taskruntime

import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.time.Duration
import java.time.Instant

object NoopFeatureTaskRuntimeHeartbeat : FeatureTaskRuntimeHeartbeat {
  private const val NAME = "NoopFeatureTaskRuntimeHeartbeat"

  override fun stop() {
  }

  override fun fencingLostReason(): String? {
    return null
  }
}

object NoopFeatureTaskRuntimeWorkerSupervisor : FeatureTaskRuntimeWorkerSupervisor {
  private const val NAME = "NoopFeatureTaskRuntimeWorkerSupervisor"

  override fun currentProcess(): FeatureTaskRuntimeProcessIdentity {
    return FeatureTaskRuntimeProcessIdentity("noop-host", "noop-boot", 1, "noop-birth")
  }

  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection {
    return FeatureTaskRuntimeProcessInspection.Unsupported("no-op supervisor performs no liveness inspection")
  }

  override fun processStartedAt(ownership: FeatureTaskRuntimeWorkerOwnership): Instant? = null

  override fun awaitExit(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    timeout: Duration,
  ) {
  }

  override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean {
    return false
  }

  override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean {
    return false
  }

  override fun startHeartbeat(
    plan: FeatureTaskRuntimeHeartbeatPlan,
    heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  ): FeatureTaskRuntimeHeartbeat {
    return NoopFeatureTaskRuntimeHeartbeat
  }

  override fun pause(durationMillis: Long) {
  }
}
