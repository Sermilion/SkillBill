package skillbill.engine.featuretask.lifecycle.checkpoint

internal fun auditReviewCheckpointWarning(
  branch: String,
  error: String,
): String =
  "Feature-task-runtime could not checkpoint the audited implementation on the feature branch '$branch' " +
    "before review" + (if (error.isBlank()) "." else " ($error).") +
    " Reviewing the branch as it stands."
