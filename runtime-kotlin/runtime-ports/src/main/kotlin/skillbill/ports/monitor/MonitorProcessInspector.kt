package skillbill.ports.monitor

import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection

/**
 * Process identity evidence for standalone monitor ownership. [inspect] reports death only with positive exact
 * evidence; a reused pid, an unverifiable birth, or an unreadable process table is never reported as dead.
 */
interface MonitorProcessInspector {
  fun currentProcess(): FeatureTaskRuntimeProcessIdentity

  fun inspect(recorded: FeatureTaskRuntimeProcessIdentity): FeatureTaskRuntimeProcessInspection
}
