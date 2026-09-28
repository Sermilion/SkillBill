package skillbill.engine.featuretask.runner

import skillbill.engine.featuretask.slot.execution.FeatureTaskRuntimeExecutionEntry
import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.runloop.durable.FeatureTaskRuntimeRunInvariantsStore

@Inject
class FeatureTaskRuntimeRunStartup(
  val crashReconciler: FeatureTaskRuntimeCrashReconciler,
  val executionEntry: FeatureTaskRuntimeExecutionEntry,
  val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
)
