package skillbill.cli.goal.export

import skillbill.contracts.workflow.goal.GoalArchiveEnvelopeKeys

object GoalRunnerExportPayloadKeys {
  const val FEATURE_BRANCH: String = GoalArchiveEnvelopeKeys.FEATURE_BRANCH
  const val COMMIT_SHA: String = GoalArchiveEnvelopeKeys.COMMIT_SHA
  const val ARCHIVE_PATH: String = "archive_path"
  const val FILE_COUNT: String = "file_count"
  const val ROW_COUNTS: String = "row_counts"
  const val REFUSAL_REASON: String = "refusal_reason"
}
