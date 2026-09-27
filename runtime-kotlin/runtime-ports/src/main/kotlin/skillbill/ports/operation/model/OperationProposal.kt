package skillbill.ports.operation.model

data class OperationProposal(
  val token: String,
  val operationId: String,
  val repoRoot: String,
  val anchors: OperationAnchors,
  val proposalValue: String,
  val createdAt: String,
  val supersededAt: String? = null,
  val consumedAt: String? = null,
)

data class OperationAnchors(
  val headSha: String,
  val branch: String,
  val operationValues: Map<String, String> = emptyMap(),
)
