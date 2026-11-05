package skillbill.contracts.workflow.goal

import skillbill.contracts.SharedPayloadKeys

object GoalArchiveEnvelopeKeys {
  const val FEATURE_BRANCH: String = "feature_branch"
  const val COMMIT_SHA: String = "commit_sha"
  const val EXPORTED_AT: String = "exported_at"
  const val ARCHIVE_JSON: String = "archive.json"
  const val SPECS_PREFIX: String = "specs/"
  const val WORKFLOWS_JSON: String = "records/workflows.json"
  const val PLANNING_JSON: String = "records/planning.json"
  const val OWNED_TABLES_JSON: String = "records/owned-tables.json"
}

fun goalArchiveEnvelopeWireMap(
  contractVersion: String,
  issueKey: String,
  featureBranch: String,
  commitSha: String,
  exportedAt: String,
): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.CONTRACT_VERSION to contractVersion,
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    GoalArchiveEnvelopeKeys.FEATURE_BRANCH to featureBranch,
    GoalArchiveEnvelopeKeys.COMMIT_SHA to commitSha,
    GoalArchiveEnvelopeKeys.EXPORTED_AT to exportedAt,
  )
