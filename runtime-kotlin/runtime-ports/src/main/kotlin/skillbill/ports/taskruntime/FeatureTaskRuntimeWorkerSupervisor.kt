package skillbill.ports.taskruntime

import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.time.Duration
import java.time.Instant

interface FeatureTaskRuntimeWorkerSupervisor {
  fun currentProcess(): FeatureTaskRuntimeProcessIdentity

  fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection

  /** Returns the start time only when [ownership] identifies an exact live process. */
  fun processStartedAt(ownership: FeatureTaskRuntimeWorkerOwnership): Instant?

  /**
   * Block until [ownership] is no longer [FeatureTaskRuntimeProcessInspection.ExactLive], or until
   * [timeout] elapses. Used by a second foreground `skill-bill goal` that raced the same launch.
   * On timeout the peer may still be live — the caller re-inspects and fails closed. A no-op default
   * keeps tests and artifact-only seams from waiting. Must not wait on the current process.
   */
  fun awaitExit(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    timeout: Duration,
  )

  fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean

  fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean

  fun startHeartbeat(
    plan: FeatureTaskRuntimeHeartbeatPlan,
    heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  ): FeatureTaskRuntimeHeartbeat

  fun pause(durationMillis: Long)
}

interface FeatureTaskRuntimeHeartbeat {
  fun stop()

  /**
   * The reason renewal ended because another owner holds the lease, or null while this process is
   * still the fenced owner. The run owner must consult this before reporting its phase successful:
   * only lease operations are owner-token fenced, so a de-fenced process that kept running would go
   * on writing workflow state alongside the owner that displaced it.
   */
  fun fencingLostReason(): String?
}
