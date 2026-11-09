package skillbill.ports.db

import skillbill.ports.db.model.RetainedWorkflowRecord
import skillbill.ports.db.model.RuntimeDatabaseReadinessResult

object ReadyRuntimeDatabaseReadiness : RuntimeDatabaseReadiness {
  override fun prepareExisting(): RuntimeDatabaseReadinessResult = RuntimeDatabaseReadinessResult.Ready(true)

  override fun initializeRead(): RuntimeDatabaseReadinessResult = prepareExisting()

  override fun observeRead(): RuntimeDatabaseReadinessResult = RuntimeDatabaseReadinessResult.Ready(true)
}

object EmptyRetainedWorkflowReader : RetainedWorkflowReader {
  override fun read(workflowId: String?): List<RetainedWorkflowRecord> = emptyList()
}
