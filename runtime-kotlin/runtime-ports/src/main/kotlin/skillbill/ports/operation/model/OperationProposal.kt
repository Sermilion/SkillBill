package skillbill.ports.operation.model

/**
 * A stored confirmation proposal. [proposalValue] is the proposal step's prose, executed verbatim on confirm.
 * [anchors] is the repository state the proposal was computed against; confirm refuses once any anchor moved.
 */
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

/**
 * Every proposal anchors HEAD and the branch. [operationValues] carries the anchors the operation itself owns plus
 * any value it pins for confirm.
 */
data class OperationAnchors(
  val headSha: String,
  val branch: String,
  val operationValues: Map<String, String> = emptyMap(),
)
