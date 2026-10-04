package skillbill.engine.featuretask.runner

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionEntry
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCrashReconciliationResult
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution

@Inject
class FeatureTaskRuntimeRunStartup(
  private val crashReconciler: FeatureTaskRuntimeCrashReconciler,
  private val executionEntry: FeatureTaskRuntimeExecutionEntry,
) {
  fun admit(request: FeatureTaskRuntimeRunRequest): AdmittedFeatureTaskRuntimeExecution = executionEntry.admit(request)

  fun reconcile(): FeatureTaskRuntimeCrashReconciliationResult = crashReconciler.reconcile()
}
