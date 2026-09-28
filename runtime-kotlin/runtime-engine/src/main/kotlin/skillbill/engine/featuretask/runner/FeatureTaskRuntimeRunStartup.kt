package skillbill.engine.featuretask.runner

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.runloop.durable.FeatureTaskRuntimeRunInvariantsStore
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionEntry

@Inject
class FeatureTaskRuntimeRunStartup(
  val crashReconciler: FeatureTaskRuntimeCrashReconciler,
  val executionEntry: FeatureTaskRuntimeExecutionEntry,
  val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
)
