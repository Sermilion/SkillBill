package skillbill.cli.goal.import

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.goal.GoalArchiveEnvelopeKeys

object GoalRunnerImportPayloadKeys {
  const val ISSUE_KEY: String = SharedPayloadKeys.ISSUE_KEY
  const val FEATURE_BRANCH: String = GoalArchiveEnvelopeKeys.FEATURE_BRANCH
  const val COMMIT_SHA: String = GoalArchiveEnvelopeKeys.COMMIT_SHA
  const val ARCHIVE_PATH: String = "archive_path"
  const val ROW_COUNTS: String = "row_counts"
  const val REFUSAL_REASON: String = "refusal_reason"
  const val MISSING_SPEC_PATHS: String = "missing_spec_paths"
}
