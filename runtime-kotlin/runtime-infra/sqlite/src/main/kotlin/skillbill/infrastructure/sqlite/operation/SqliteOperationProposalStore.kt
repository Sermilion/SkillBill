package skillbill.infrastructure.sqlite.operation

import skillbill.contracts.JsonCodec
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.operation.OperationProposalRepository
import skillbill.ports.operation.model.OperationAnchors
import skillbill.ports.operation.model.OperationProposal
import java.sql.Connection
import java.sql.ResultSet

/** Wire keys of the `anchors_json` column. */
internal object OperationProposalPayloadKeys {
  const val HEAD_SHA = "head_sha"
  const val BRANCH = "branch"
  const val OPERATION_VALUES = "operation_values"
}

internal class SqliteOperationProposalStore(
  private val connection: Connection,
) : OperationProposalRepository {
  override fun createSupersedingPrior(proposal: OperationProposal) {
    connection.prepareStatement(
      """
      UPDATE operation_proposals SET superseded_at = ?
      WHERE operation_id = ? AND repo_root = ? AND consumed_at IS NULL AND superseded_at IS NULL
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(proposal.createdAt, proposal.operationId, proposal.repoRoot)
      statement.executeUpdate()
    }
    connection.prepareStatement(
      """
      INSERT INTO operation_proposals (
        token, operation_id, repo_root, anchors_json, proposal_value, created_at, superseded_at, consumed_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        proposal.token,
        proposal.operationId,
        proposal.repoRoot,
        encodeAnchors(proposal.anchors),
        proposal.proposalValue,
        proposal.createdAt,
        proposal.supersededAt,
        proposal.consumedAt,
      )
      statement.executeUpdate()
    }
  }

  override fun find(token: String): OperationProposal? {
    connection.prepareStatement(
      """
      SELECT token, operation_id, repo_root, anchors_json, proposal_value, created_at, superseded_at, consumed_at
      FROM operation_proposals WHERE token = ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(token)
      statement.executeQuery().use { rows ->
        return if (rows.next()) rows.toProposal() else null
      }
    }
  }

  override fun markConsumed(
    token: String,
    consumedAt: String,
  ): Boolean {
    connection.prepareStatement(
      """
      UPDATE operation_proposals SET consumed_at = ?
      WHERE token = ? AND consumed_at IS NULL AND superseded_at IS NULL
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(consumedAt, token)
      return statement.executeUpdate() > 0
    }
  }

  private fun ResultSet.toProposal(): OperationProposal =
    OperationProposal(
      token = getString("token"),
      operationId = getString("operation_id"),
      repoRoot = getString("repo_root"),
      anchors = decodeAnchors(getString("anchors_json")),
      proposalValue = getString("proposal_value"),
      createdAt = getString("created_at"),
      supersededAt = getString("superseded_at"),
      consumedAt = getString("consumed_at"),
    )

  private fun encodeAnchors(anchors: OperationAnchors): String =
    JsonCodec.mapToJsonString(
      mapOf(
        OperationProposalPayloadKeys.HEAD_SHA to anchors.headSha,
        OperationProposalPayloadKeys.BRANCH to anchors.branch,
        OperationProposalPayloadKeys.OPERATION_VALUES to anchors.operationValues,
      ),
    )

  private fun decodeAnchors(raw: String): OperationAnchors {
    val payload =
      JsonCodec.parseObjectOrNull(raw)?.let { JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(it)) }
        ?: error("operation_proposals.anchors_json is not a JSON object.")
    val operationValues = JsonCodec.anyToStringAnyMap(payload[OperationProposalPayloadKeys.OPERATION_VALUES])
    return OperationAnchors(
      headSha = payload[OperationProposalPayloadKeys.HEAD_SHA].toString(),
      branch = payload[OperationProposalPayloadKeys.BRANCH].toString(),
      operationValues = operationValues.orEmpty().mapValues { (_, value) -> value.toString() },
    )
  }
}
