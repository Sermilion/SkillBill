package skillbill.engine.goalrunner.persist

import skillbill.engine.goalrunner.model.CrashReconcileExpiredWorkerRequest

internal fun crashReconcileExpiredWorkerToResumable(
  request: CrashReconcileExpiredWorkerRequest,
  clock: java.time.Clock,
): skillbill.goalrunner.model.GoalRunnerStoredOutcome? = null
