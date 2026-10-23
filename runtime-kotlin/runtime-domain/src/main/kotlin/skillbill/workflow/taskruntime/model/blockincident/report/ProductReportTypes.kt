package skillbill.workflow.taskruntime.model.blockincident.report

import skillbill.contracts.workflow.identity.blockincident.BlockIncidentReportKeys

enum class ProductReportStatus(val wireValue: String) {
  LOCAL_ONLY(BlockIncidentReportKeys.STATUS_LOCAL_ONLY),
  PENDING(BlockIncidentReportKeys.STATUS_PENDING),
  SUBMITTING(BlockIncidentReportKeys.STATUS_SUBMITTING),
  AMBIGUOUS(BlockIncidentReportKeys.STATUS_AMBIGUOUS),
  DELIVERED(BlockIncidentReportKeys.STATUS_DELIVERED),
  LINKED_EXISTING(BlockIncidentReportKeys.STATUS_LINKED_EXISTING),
  FAILED(BlockIncidentReportKeys.STATUS_FAILED),
}

enum class ProductReportFailure(val wireValue: String) {
  MISSING_AUTH(BlockIncidentReportKeys.FAILURE_MISSING_AUTH),
  OFFLINE(BlockIncidentReportKeys.FAILURE_OFFLINE),
  PERMISSION_REFUSED(BlockIncidentReportKeys.FAILURE_PERMISSION_REFUSED),
  RATE_LIMITED(BlockIncidentReportKeys.FAILURE_RATE_LIMITED),
  SUBMISSION_FAILED(BlockIncidentReportKeys.FAILURE_SUBMISSION_FAILED),
  RESIDUAL_SECRET(BlockIncidentReportKeys.FAILURE_RESIDUAL_SECRET),
  RETRIES_EXHAUSTED(BlockIncidentReportKeys.FAILURE_RETRIES_EXHAUSTED),
}

enum class ProductReportFieldOutcome(val wireValue: String) {
  KEPT(BlockIncidentReportKeys.OUTCOME_KEPT),
  REDACTED(BlockIncidentReportKeys.OUTCOME_REDACTED),
  TRUNCATED(BlockIncidentReportKeys.OUTCOME_TRUNCATED),
  DROPPED(BlockIncidentReportKeys.OUTCOME_DROPPED),
}

enum class ProductReportCommentState(val wireValue: String) {
  NOT_REQUIRED(BlockIncidentReportKeys.COMMENT_NOT_REQUIRED),
  PENDING(BlockIncidentReportKeys.COMMENT_PENDING),
  POSTED(BlockIncidentReportKeys.COMMENT_POSTED),
}
