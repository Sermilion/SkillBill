package skillbill.infrastructure.sqlite.operation

import skillbill.contracts.operationcard.OperationCardContractJson
import skillbill.contracts.operationcard.OperationCardPayloadKeys
import skillbill.contracts.operationcard.OperationCardProjection
import skillbill.contracts.operationcard.OperationCardStatus
import skillbill.contracts.operationcard.OperationLaunchContext
import skillbill.contracts.operationcard.OperationLaunchRetention
import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.operationcard.OperationCardFailureCode
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.operation.OperationCardRepository
import skillbill.ports.operation.model.ConfirmationExecution
import skillbill.ports.operation.model.OperationProposal
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import java.sql.Connection
import java.sql.SQLException

internal class SqliteOperationCardStore(
  private val connection: Connection,
) : OperationCardRepository {
  private val proposals = SqliteOperationProposalStore(connection)
  private val executions = SqliteConfirmationExecutions(connection)

  override fun databaseIdentity(): String =
    guarded {
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT store_id FROM ${DesktopReadSchema.IDENTITY_TABLE} WHERE id = 1").use { rows ->
          if (rows.next()) rows.getString("store_id") else throw SQLException("Work store identity is missing.")
        }
      }
    }

  override fun saveLaunchContext(context: OperationLaunchContext) {
    guarded {
      connection.prepareStatement(
        """
        INSERT INTO operation_launch_contexts (
          launch_id, operation_id, repository_identity, database_identity, checkout_root, branch,
          original_arguments_json, selected_agent, selected_profile, holder_id, retention
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(
          context.launchId,
          context.operationId,
          context.repositoryIdentity,
          context.databaseIdentity,
          context.checkoutRoot,
          context.branch,
          OperationCardContractJson.encodeArguments(context.originalArguments),
          context.selectedAgent,
          context.selectedProfile,
          context.holderId,
          context.retention?.wireValue,
        )
        statement.executeUpdate()
      }
    }
  }

  override fun findLaunchContextByInvocation(invocationId: String): OperationLaunchContext? =
    guarded {
      connection.prepareStatement(
        """
        SELECT launch_id FROM operation_confirmation_executions
        WHERE invocation_id = ? AND launch_id IS NOT NULL
        ORDER BY execution_id DESC LIMIT 1
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(invocationId)
        statement.executeQuery().use { rows ->
          if (!rows.next()) return@use null
          findLaunchContext(rows.getString("launch_id"))
        }
      }
    }

  override fun findLaunchContext(launchId: String): OperationLaunchContext? =
    guarded {
      connection.prepareStatement(
        """
        SELECT launch_id, operation_id, repository_identity, database_identity, checkout_root, branch,
          original_arguments_json, selected_agent, selected_profile, holder_id, retention
        FROM operation_launch_contexts WHERE launch_id = ?
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(launchId)
        statement.executeQuery().use { rows ->
          if (!rows.next()) return@use null
          OperationLaunchContext(
            launchId = rows.getString("launch_id"),
            operationId = rows.getString("operation_id"),
            repositoryIdentity = rows.getString("repository_identity"),
            databaseIdentity = rows.getString("database_identity"),
            checkoutRoot = rows.getString("checkout_root"),
            branch = rows.getString("branch"),
            originalArguments = OperationCardContractJson.decodeArguments(rows.getString("original_arguments_json")),
            selectedAgent = rows.getString("selected_agent"),
            selectedProfile = rows.getString("selected_profile"),
            holderId = rows.getString("holder_id"),
            retention =
              rows.getString("retention")?.let { value ->
                OperationLaunchRetention.fromWire(value)
                  ?: throw SQLException("Unknown operation launch retention '$value'.")
              },
          )
        }
      }
    }

  override fun publish(
    card: OperationCardProjection,
    authority: OperationProposal?,
  ): List<OperationLaunchContext> =
    guarded {
      authority?.let(proposals::createSupersedingPrior)
      val priors = awaitingPriors(card)
      priors.forEach { prior -> supersedeAwaiting(prior.token) }
      connection.prepareStatement(
        """
        INSERT INTO operation_proposal_cards (token, launch_id, operation_id, repository_identity, status, record_json)
        VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(
          card.token,
          card.launchId,
          card.operationId,
          card.repositoryIdentity,
          card.status.wireValue,
          OperationCardContractJson.encode(card),
        )
        statement.executeUpdate()
      }
      priors.mapNotNull { prior -> findLaunchContext(prior.launchId) }
    }

  override fun find(token: String): OperationCardProjection? =
    guarded { readCard("SELECT record_json FROM operation_proposal_cards WHERE token = ?", token) }

  override fun findByLaunch(launchId: String): OperationCardProjection? =
    guarded {
      readCard(
        "SELECT record_json FROM operation_proposal_cards WHERE launch_id = ? ORDER BY rowid DESC LIMIT 1",
        launchId,
      )
    }

  override fun admitConfirmation(
    token: String,
    selection: String?,
    owner: FeatureTaskRuntimeProcessIdentity,
  ): ConfirmationExecution? =
    guarded {
      val card = readCard("SELECT record_json FROM operation_proposal_cards WHERE token = ?", token)
      val admitted =
        card != null &&
          transition(
            token,
            OperationCardStatus.AWAITING_CONFIRMATION,
            OperationCardStatus.CONFIRMING,
            RecordedCardFacts(selection = selection),
          )
      card?.takeIf { admitted }?.let { executions.insertAdmitted(token, it.launchId, owner) }
    }

  override fun activeExecution(token: String): ConfirmationExecution? = guarded { executions.active(token) }

  override fun claimConfirmation(
    token: String,
    launchId: String,
    invocationId: String,
    owner: FeatureTaskRuntimeProcessIdentity,
  ): Boolean = guarded { executions.claim(token, launchId, invocationId, owner) }

  override fun consumeClaimedConfirmation(
    token: String,
    launchId: String,
    invocationId: String,
    at: String,
  ): Boolean = guarded { executions.isClaimedBy(token, launchId, invocationId) && proposals.markConsumed(token, at) }

  override fun settleConfirmation(
    token: String,
    executionId: Long,
    to: OperationCardStatus,
    evidence: String?,
    at: String,
  ): Boolean =
    guarded {
      val active = executions.active(token)
      val settled =
        active != null && active.executionId == executionId &&
          transition(
            token,
            OperationCardStatus.CONFIRMING,
            to,
            RecordedCardFacts(confirmationInvocationId = active.invocationId, evidence = evidence),
          )
      if (settled) {
        executions.retire(executionId, to != OperationCardStatus.AWAITING_CONFIRMATION, evidence)
        if (to == OperationCardStatus.REFUSED) supersedeAuthority(token, at)
      }
      settled
    }

  override fun settleUnrecordedConfirmation(
    token: String,
    to: OperationCardStatus,
    evidence: String?,
    at: String,
  ): Boolean =
    guarded {
      val settled =
        executions.active(token) == null &&
          transition(token, OperationCardStatus.CONFIRMING, to, RecordedCardFacts(evidence = evidence))
      if (settled && to == OperationCardStatus.REFUSED) supersedeAuthority(token, at)
      settled
    }

  override fun pendingCleanup(token: String): ConfirmationExecution? = guarded { executions.pendingCleanup(token) }

  override fun completeCleanup(executionId: Long) {
    guarded { executions.completeCleanup(executionId) }
  }

  private fun transition(
    token: String,
    from: OperationCardStatus,
    to: OperationCardStatus,
    recorded: RecordedCardFacts = RecordedCardFacts(),
  ): Boolean =
    guarded {
      connection.prepareStatement(
        """
        UPDATE operation_proposal_cards SET status = ?, record_json = json_set(
          record_json,
          '${path(OperationCardPayloadKeys.STATUS)}', ?,
          '${path(OperationCardPayloadKeys.CONFIRMATION_INVOCATION_ID)}',
            COALESCE(?, json_extract(record_json, '${path(OperationCardPayloadKeys.CONFIRMATION_INVOCATION_ID)}')),
          '${path(OperationCardPayloadKeys.SELECTION)}',
            COALESCE(?, json_extract(record_json, '${path(OperationCardPayloadKeys.SELECTION)}')),
          '${path(OperationCardPayloadKeys.EVIDENCE)}',
            COALESCE(?, json_extract(record_json, '${path(OperationCardPayloadKeys.EVIDENCE)}'))
        )
        WHERE token = ? AND status = ? AND json_valid(record_json)
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(
          to.wireValue,
          to.wireValue,
          recorded.confirmationInvocationId,
          recorded.selection,
          recorded.evidence,
          token,
          from.wireValue,
        )
        statement.executeUpdate() == 1
      }
    }

  override fun dismiss(
    token: String,
    at: String,
  ): Boolean =
    guarded {
      val cancelled = transition(token, OperationCardStatus.AWAITING_CONFIRMATION, OperationCardStatus.CANCELLED)
      if (cancelled) supersedeAuthority(token, at)
      cancelled
    }

  private fun supersedeAuthority(
    token: String,
    at: String,
  ) {
    connection.prepareStatement(
      "UPDATE operation_proposals SET superseded_at = ? WHERE token = ? AND consumed_at IS NULL " +
        "AND superseded_at IS NULL",
    ).use { statement ->
      statement.bindAll(at, token)
      statement.executeUpdate()
    }
  }

  private fun supersedeAwaiting(token: String) {
    connection.prepareStatement(
      """
      UPDATE operation_proposal_cards SET status = ?, record_json = CASE WHEN json_valid(record_json)
        THEN json_set(record_json, '${path(OperationCardPayloadKeys.STATUS)}', ?) ELSE record_json END
      WHERE token = ? AND status = ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        OperationCardStatus.SUPERSEDED.wireValue,
        OperationCardStatus.SUPERSEDED.wireValue,
        token,
        OperationCardStatus.AWAITING_CONFIRMATION.wireValue,
      )
      statement.executeUpdate()
    }
  }

  private fun awaitingPriors(card: OperationCardProjection): List<PriorCard> =
    connection.prepareStatement(
      """
      SELECT c.token, c.launch_id FROM operation_proposal_cards c
      JOIN operation_launch_contexts l ON l.launch_id = c.launch_id
      WHERE c.repository_identity = ? AND c.operation_id = ? AND l.checkout_root = ? AND c.status = ?
        AND c.token != ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        card.repositoryIdentity,
        card.operationId,
        card.checkoutRoot,
        OperationCardStatus.AWAITING_CONFIRMATION.wireValue,
        card.token,
      )
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) add(PriorCard(rows.getString("token"), rows.getString("launch_id")))
        }
      }
    }

  private fun readCard(
    sql: String,
    key: String,
  ): OperationCardProjection? =
    connection.prepareStatement(sql).use { statement ->
      statement.bindAll(key)
      statement.executeQuery().use { rows ->
        if (rows.next()) OperationCardContractJson.decode(rows.getString("record_json")) else null
      }
    }

  private inline fun <T> guarded(operation: () -> T): T =
    try {
      operation()
    } catch (failure: SQLException) {
      throw SkillBillRuntimeException(
        OperationCardFailureCode.OPERATION_CARD_STORE_FAILURE,
        "The operation card store failed: ${failure.message}",
        failure,
      )
    }

  private data class PriorCard(val token: String, val launchId: String)

  private data class RecordedCardFacts(
    val confirmationInvocationId: String? = null,
    val selection: String? = null,
    val evidence: String? = null,
  )
}

private fun path(key: String): String = "$" + "." + key
