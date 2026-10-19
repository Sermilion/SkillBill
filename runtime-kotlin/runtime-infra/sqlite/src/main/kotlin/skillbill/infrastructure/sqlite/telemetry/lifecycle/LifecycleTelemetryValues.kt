package skillbill.infrastructure.sqlite.telemetry.lifecycle

import skillbill.telemetry.model.FeatureVerifyFinishedRecord

internal fun featureVerifyFinishedValues(
  record: FeatureVerifyFinishedRecord,
  gapsFoundJson: String,
  includeSessionFirst: Boolean,
): List<Any?> =
  buildList {
    if (includeSessionFirst) {
      add(record.sessionId)
    }
    add(record.featureFlagAuditPerformed.toSqlInt())
    add(record.reviewIterations)
    add(record.auditResult)
    add(record.completionStatus)
    add(record.historyRelevance)
    add(record.historyHelpfulness)
    add(gapsFoundJson)
    add(record.qaOutcome)
    add(record.qaReason)
    if (!includeSessionFirst) {
      add(record.sessionId)
    }
  }
