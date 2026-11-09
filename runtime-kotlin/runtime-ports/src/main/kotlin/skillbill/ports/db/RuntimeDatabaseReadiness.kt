package skillbill.ports.db

import skillbill.ports.db.model.RetainedWorkflowRecord
import skillbill.ports.db.model.RuntimeDatabaseReadinessResult

interface RuntimeDatabaseReadiness {
  /**
   * Prepares an existing store before any read snapshot or worker admission transaction.
   * Never creates an absent store.
   */
  fun prepareExisting(): RuntimeDatabaseReadinessResult

  /** Starts an owned read lifecycle once, then observes the selected store without migration. */
  fun initializeRead(): RuntimeDatabaseReadinessResult

  /** Reads readiness without migration, worker acquisition, or cache mutation. */
  fun observeRead(): RuntimeDatabaseReadinessResult
}

interface RetainedWorkflowReader {
  /** Reads source text without normal artifact restoration or any migration or cache mutation. */
  fun read(workflowId: String?): List<RetainedWorkflowRecord>
}
