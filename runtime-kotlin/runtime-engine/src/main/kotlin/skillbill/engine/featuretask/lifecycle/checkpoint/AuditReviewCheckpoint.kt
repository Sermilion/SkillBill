package skillbill.engine.featuretask.lifecycle.checkpoint

internal fun auditReviewCheckpointBlockedReason(
  branch: String,
  error: String,
): String =
  "Feature-task-runtime could not commit the audited implementation on the feature branch '$branch' " +
    "before review" + (if (error.isBlank()) "." else " ($error).") +
    " Refusing to review an uncommitted final audit iteration."
