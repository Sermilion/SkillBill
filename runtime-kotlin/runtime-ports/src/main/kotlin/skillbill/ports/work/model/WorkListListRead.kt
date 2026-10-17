package skillbill.ports.work.model

data class WorkListRejectedRow(
  val workflowId: String,
  val detail: String,
)

data class WorkListListRead(
  val items: List<WorkItem>,
  val rejectedRows: List<WorkListRejectedRow> = emptyList(),
)
