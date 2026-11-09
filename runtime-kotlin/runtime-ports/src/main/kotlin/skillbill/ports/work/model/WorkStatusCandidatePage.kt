package skillbill.ports.work.model

import skillbill.error.core.RuntimeFailureCode

data class WorkStatusCandidateQuery(
  val afterKey: String?,
  val limit: Int,
  val liveStates: Set<String>,
)

data class WorkStatusCandidateRejectedRow(
  val workflowId: String,
  val pagingKey: String,
  val failureCode: RuntimeFailureCode,
  val detail: String,
)

data class WorkStatusCandidatePage(
  val items: List<WorkItem>,
  val lastKey: String?,
  val examinedCount: Int = items.size,
  val rejectedRows: List<WorkStatusCandidateRejectedRow> = emptyList(),
)
