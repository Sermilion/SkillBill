package skillbill.engine.featuretask.runner

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.runloop.durable.FeatureTaskRuntimeRunInvariantsStore

@Inject
class FeatureTaskRuntimeRunStartup(
  val crashReconciler: FeatureTaskRuntimeCrashReconciler,
  val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
)
