package skillbill.ports.taskruntime

import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership

interface WorkOwnerExitObserver {
  fun observe(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    onExit: () -> Unit,
  ): AutoCloseable?
}
