package skillbill.ports.idestatus

import skillbill.ports.idestatus.model.WorkValidationBranchRun

interface StandaloneValidationBranchRepository {
  /**
   * Returns rows with `phase_id = 'validation'` sharing the invocation's `repository_identity` and equal non-null
   * `branch_correlation`, including the invocation row when it is a validation row, newest started first, then highest
   * run sequence and invocation id. The result is capped at
   * [skillbill.ports.idestatus.model.VALIDATION_BRANCH_RUN_LIMIT] rows and is empty for an unknown invocation or a
   * null or `HEAD` branch correlation.
   */
  fun readValidationBranchRuns(invocationId: String): List<WorkValidationBranchRun>
}
