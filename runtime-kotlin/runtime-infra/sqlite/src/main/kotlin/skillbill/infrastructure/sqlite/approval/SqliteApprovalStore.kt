package skillbill.infrastructure.sqlite.approval

import skillbill.approval.model.ApprovalActionClass
import skillbill.approval.model.ApprovalExecutionMode
import skillbill.approval.model.ApprovalGrantFacts
import skillbill.approval.model.ApprovalProviderDisposition
import skillbill.approval.model.sameApprovalRegistrationPayload
import skillbill.contracts.approval.ApprovalContractJson
import skillbill.contracts.approval.ApprovalProviderRequestId
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.approval.ApprovalRuleRecord
import skillbill.contracts.approval.ApprovalState
import skillbill.contracts.approval.OPERATOR_APPROVAL_CONTRACT_VERSION
import skillbill.error.approval.ApprovalFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.approval.ApprovalStore
import skillbill.ports.approval.model.ApprovalConsumerLease
import skillbill.ports.approval.model.ApprovalDeliveryProgress
import skillbill.ports.approval.model.ApprovalDeliveryState
import skillbill.ports.approval.model.ApprovalOwnerKey
import skillbill.ports.approval.model.ApprovalOwnerLease
import skillbill.ports.approval.model.ApprovalRegistration
import skillbill.ports.approval.model.ApprovalRequestIdentity
import skillbill.ports.approval.model.ApprovalStoredRequest
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

internal class SqliteApprovalStore(
  private val connection: Connection,
  private val diagnostics: RuntimeDiagnostics,
) : ApprovalStore {
  override fun register(
    request: ApprovalRequestRecord,
    facts: ApprovalGrantFacts,
  ): ApprovalRegistration {
    if (request.contractVersion != OPERATOR_APPROVAL_CONTRACT_VERSION) {
      diagnostics.warning("Unsupported operator approval contract version ${request.contractVersion}.")
      throw SkillBillRuntimeException(
        ApprovalFailureCode.APPROVAL_VERSION_MISMATCH,
        "Unsupported operator approval contract version ${request.contractVersion}.",
      )
    }
    return try {
      connection.prepareStatement(
        """
        INSERT INTO approval_requests (
          approval_id, repository_identity, run_id, process_generation, provider,
          provider_request_id_type, provider_request_id, record_json, terminal_decision_json,
          action_class, write_capable, input_complete, provider_disposition
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(
          identityValues(request.identity()) +
            listOf(
              encode(request),
              if (request.state == ApprovalState.PENDING) null else encode(request),
              facts.actionClass.wireValue,
              if (facts.writeCapable) 1 else 0,
              if (facts.inputComplete) 1 else 0,
              facts.providerDisposition.wireValue,
            ),
        )
        statement.executeUpdate()
      }
      ApprovalRegistration.Created
    } catch (_: SQLException) {
      existingRegistration(request)
    }
  }

  override fun find(identity: ApprovalRequestIdentity): ApprovalStoredRequest? =
    connection.prepareStatement(
      """
      SELECT $STORED_REQUEST_COLUMNS FROM approval_requests WHERE $IDENTITY_MATCH
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(identityValues(identity))
      statement.executeQuery().use { rows -> if (rows.next()) storedRequest(rows) else null }
    }

  override fun settleAndClaim(
    identity: ApprovalRequestIdentity,
    settled: ApprovalRequestRecord,
    rule: ApprovalRuleRecord?,
    claimedAt: String,
  ): Boolean {
    if (settled.state == ApprovalState.PENDING || settled.identity() != identity) return false
    if (rule != null && !rule.scopes(settled)) return false
    val updated =
      connection.prepareStatement(
        """
        UPDATE approval_requests SET record_json = ?, terminal_decision_json = ?, dispatch_claimed_at = ?,
          delivery_state = 'claimed'
        WHERE $IDENTITY_MATCH AND terminal_decision_json IS NULL
          AND json_extract(record_json, '$.state') = 'pending'
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(listOf(encode(settled), encode(settled), claimedAt) + identityValues(identity))
        statement.executeUpdate()
      }
    if (updated != 1) return false
    rule?.let(::insertRule)
    return true
  }

  override fun settleWithoutDelivery(
    identity: ApprovalRequestIdentity,
    settled: ApprovalRequestRecord,
  ): Boolean {
    if (settled.state == ApprovalState.PENDING || settled.identity() != identity) return false
    return connection.prepareStatement(
      """
      UPDATE approval_requests SET record_json = ?, terminal_decision_json = COALESCE(terminal_decision_json, ?),
        delivery_state = 'not_required'
      WHERE $IDENTITY_MATCH AND (delivery_state = 'claimed' OR terminal_decision_json IS NULL)
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(listOf(encode(settled), encode(settled)) + identityValues(identity))
      statement.executeUpdate() == 1
    }
  }

  override fun recordProviderDisposition(
    identity: ApprovalRequestIdentity,
    disposition: ApprovalProviderDisposition,
  ): Boolean =
    connection.prepareStatement(
      "UPDATE approval_requests SET provider_disposition = ? WHERE $IDENTITY_MATCH",
    ).use { statement ->
      statement.bindAll(listOf(disposition.wireValue) + identityValues(identity))
      statement.executeUpdate() == 1
    }

  override fun finishDelivery(
    identity: ApprovalRequestIdentity,
    state: ApprovalDeliveryState,
  ): Boolean {
    val updated =
      connection.prepareStatement(
        """
        UPDATE approval_requests SET delivery_state = ? WHERE $IDENTITY_MATCH AND delivery_state = 'claimed'
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(listOf(state.databaseValue) + identityValues(identity))
        statement.executeUpdate() == 1
      }
    if (updated || state != ApprovalDeliveryState.NOT_REQUIRED) return updated
    return connection.prepareStatement("SELECT delivery_state FROM approval_requests WHERE $IDENTITY_MATCH").use {
        statement ->
      statement.bindAll(identityValues(identity))
      statement.executeQuery().use { rows -> rows.next() && rows.getString(1) == state.databaseValue }
    }
  }

  override fun activeRules(repositoryIdentity: String): List<ApprovalRuleRecord> =
    connection.prepareStatement(
      """
      SELECT rule_id, repository_identity, provider, profile_identity, protocol_revision, action_kind,
        working_directory, action_fingerprint, created_at, revoked_at
      FROM approval_rules WHERE repository_identity = ? AND revoked_at IS NULL ORDER BY created_at, rule_id
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(repositoryIdentity)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) add(ruleRecord(rows))
        }
      }
    }

  override fun revokeRule(
    ruleId: String,
    repositoryIdentity: String,
    revokedAt: String,
  ): Boolean =
    connection.prepareStatement(
      """
      UPDATE approval_rules SET revoked_at = ?
      WHERE rule_id = ? AND repository_identity = ? AND revoked_at IS NULL
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(revokedAt, ruleId, repositoryIdentity)
      statement.executeUpdate() == 1
    }

  override fun pending(
    repositoryIdentity: String,
    owner: ApprovalOwnerKey?,
  ): List<ApprovalStoredRequest> =
    connection.prepareStatement(
      """
      SELECT $STORED_REQUEST_COLUMNS FROM approval_requests WHERE repository_identity = ?
        AND terminal_decision_json IS NULL AND (? IS NULL OR (run_id = ? AND process_generation = ?))
      ORDER BY rowid
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(repositoryIdentity, owner?.runId, owner?.runId, owner?.processGeneration)
      storedRequests(statement.executeQuery())
    }

  override fun recent(
    repositoryIdentity: String,
    limit: Int,
  ): List<ApprovalStoredRequest> =
    connection.prepareStatement(
      """
      SELECT $STORED_REQUEST_COLUMNS FROM approval_requests WHERE repository_identity = ?
      ORDER BY rowid DESC LIMIT ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(repositoryIdentity, limit)
      storedRequests(statement.executeQuery())
    }

  override fun claimedDeliveries(owner: ApprovalOwnerKey): List<ApprovalStoredRequest> =
    connection.prepareStatement(
      """
      SELECT $STORED_REQUEST_COLUMNS FROM approval_requests WHERE repository_identity = ? AND run_id = ?
        AND process_generation = ? AND delivery_state = 'claimed' AND terminal_decision_json IS NOT NULL
      ORDER BY dispatch_claimed_at, rowid
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(owner.repositoryIdentity, owner.runId, owner.processGeneration)
      storedRequests(statement.executeQuery())
    }

  override fun saveOwner(lease: ApprovalOwnerLease) {
    connection.prepareStatement(
      """
      INSERT OR REPLACE INTO approval_owners (
        repository_identity, run_id, process_generation, nonce, execution_mode, enclosing_deadline, created_at,
        expires_at, ended_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        lease.key.repositoryIdentity,
        lease.key.runId,
        lease.key.processGeneration,
        lease.nonce,
        lease.executionMode.wireValue,
        lease.enclosingDeadline,
        lease.createdAt,
        lease.expiresAt,
        lease.endedAt,
      )
      statement.executeUpdate()
    }
  }

  override fun owner(key: ApprovalOwnerKey): ApprovalOwnerLease? =
    connection.prepareStatement(
      """
      SELECT nonce, execution_mode, enclosing_deadline, created_at, expires_at, ended_at FROM approval_owners
      WHERE repository_identity = ? AND run_id = ? AND process_generation = ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(key.repositoryIdentity, key.runId, key.processGeneration)
      statement.executeQuery().use { rows -> if (rows.next()) ownerLease(key, rows) else null }
    }

  override fun saveConsumer(lease: ApprovalConsumerLease) {
    connection.prepareStatement(
      """
      INSERT OR REPLACE INTO approval_consumers (
        repository_identity, consumer_id, nonce, created_at, expires_at, ended_at
      ) VALUES (?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        lease.repositoryIdentity,
        lease.consumerId,
        lease.nonce,
        lease.createdAt,
        lease.expiresAt,
        lease.endedAt,
      )
      statement.executeUpdate()
    }
  }

  override fun consumer(repositoryIdentity: String): ApprovalConsumerLease? =
    connection.prepareStatement(
      """
      SELECT consumer_id, nonce, created_at, expires_at, ended_at FROM approval_consumers
      WHERE repository_identity = ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(repositoryIdentity)
      statement.executeQuery().use { rows ->
        if (rows.next()) {
          ApprovalConsumerLease(
            repositoryIdentity = repositoryIdentity,
            consumerId = rows.getString("consumer_id"),
            nonce = rows.getString("nonce"),
            createdAt = rows.getString("created_at"),
            expiresAt = rows.getString("expires_at"),
            endedAt = rows.getString("ended_at"),
          )
        } else {
          null
        }
      }
    }

  private fun existingRegistration(request: ApprovalRequestRecord): ApprovalRegistration {
    val existing = find(request.identity())
    if (existing == null) {
      val error =
        SkillBillRuntimeException(
          ApprovalFailureCode.APPROVAL_STORE_FAILURE,
          "Could not register approval request.",
        )
      diagnostics.error(error.message.orEmpty(), error)
      throw error
    }
    return if (sameApprovalRegistrationPayload(existing.record, request)) {
      ApprovalRegistration.Existing(existing)
    } else {
      ApprovalRegistration.IdentityConflict
    }
  }

  private fun insertRule(rule: ApprovalRuleRecord) {
    connection.prepareStatement(
      """
      INSERT INTO approval_rules (
        rule_id, repository_identity, provider, profile_identity, protocol_revision, action_kind,
        working_directory, action_fingerprint, created_at, revoked_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        rule.ruleId,
        rule.repositoryIdentity,
        rule.provider,
        rule.profileIdentity,
        rule.protocolRevision,
        rule.actionKind,
        rule.workingDirectory,
        rule.actionFingerprint,
        rule.createdAt,
        rule.revokedAt,
      )
      statement.executeUpdate()
    }
  }

  private fun identityValues(identity: ApprovalRequestIdentity): List<String> {
    val (idType, idValue) =
      when (val requestId = identity.providerRequestId) {
        is ApprovalProviderRequestId.Text -> "string" to requestId.value
        is ApprovalProviderRequestId.Integer -> "integer" to requestId.value
      }
    return listOf(
      identity.approvalId,
      identity.repositoryIdentity,
      identity.runId,
      identity.processGeneration,
      identity.provider,
      idType,
      idValue,
    )
  }

  private fun ruleRecord(rows: ResultSet): ApprovalRuleRecord =
    ApprovalRuleRecord(
      ruleId = rows.getString("rule_id"),
      repositoryIdentity = rows.getString("repository_identity"),
      provider = rows.getString("provider"),
      profileIdentity = rows.getString("profile_identity"),
      protocolRevision = rows.getString("protocol_revision"),
      actionKind = rows.getString("action_kind"),
      workingDirectory = rows.getString("working_directory"),
      actionFingerprint = rows.getString("action_fingerprint"),
      createdAt = rows.getString("created_at"),
      revokedAt = rows.getString("revoked_at"),
    )

  private fun ownerLease(
    key: ApprovalOwnerKey,
    rows: ResultSet,
  ): ApprovalOwnerLease =
    ApprovalOwnerLease(
      key = key,
      nonce = rows.getString("nonce"),
      executionMode =
        knownToken(rows.getString("execution_mode"), ApprovalExecutionMode.entries, "owner execution mode") {
          it.wireValue
        },
      enclosingDeadline = rows.getString("enclosing_deadline"),
      createdAt = rows.getString("created_at"),
      expiresAt = rows.getString("expires_at"),
      endedAt = rows.getString("ended_at"),
    )

  private fun storedRequests(resultSet: ResultSet): List<ApprovalStoredRequest> =
    resultSet.use { rows ->
      buildList {
        while (rows.next()) add(storedRequest(rows))
      }
    }

  private fun storedRequest(rows: ResultSet): ApprovalStoredRequest {
    val actionClass =
      knownToken(rows.getString("action_class"), ApprovalActionClass.entries, "action class") { it.wireValue }
    val disposition =
      knownToken(
        rows.getString("provider_disposition"),
        ApprovalProviderDisposition.entries,
        "provider disposition",
      ) { it.wireValue }
    val delivery =
      rows.getString("delivery_state")?.takeIf { it != NOT_CLAIMED }?.let { raw ->
        knownToken(raw, ApprovalDeliveryProgress.entries, "delivery state") { it.name.lowercase() }
      }
    return ApprovalStoredRequest(
      decode(rows.getString("record_json")),
      ApprovalGrantFacts(
        actionClass,
        rows.getInt("write_capable") != 0,
        rows.getInt("input_complete") != 0,
        disposition,
      ),
      delivery,
    )
  }

  private fun <T> knownToken(
    raw: String?,
    values: List<T>,
    what: String,
    wireValue: (T) -> String,
  ): T = values.firstOrNull { value -> wireValue(value) == raw } ?: throw corruptRow(what)

  private fun corruptRow(what: String): SkillBillRuntimeException {
    val error =
      SkillBillRuntimeException(
        ApprovalFailureCode.INVALID_APPROVAL_CONTRACT,
        "Stored approval row has an unknown $what.",
      )
    diagnostics.error(error.message.orEmpty(), error)
    return error
  }

  private fun encode(request: ApprovalRequestRecord): String = ApprovalContractJson.encode(request)

  private fun decode(raw: String): ApprovalRequestRecord =
    try {
      ApprovalContractJson.decode(raw)
    } catch (failure: SkillBillRuntimeException) {
      diagnostics.error(failure.message.orEmpty(), failure)
      throw failure
    }

  private fun ApprovalRuleRecord.scopes(settled: ApprovalRequestRecord): Boolean {
    val scope = settled.authorityScope ?: return false
    return repositoryIdentity == settled.repositoryIdentity &&
      provider == settled.provider &&
      actionFingerprint == scope.actionFingerprint &&
      profileIdentity == scope.profileIdentity &&
      protocolRevision == scope.protocolRevision &&
      actionKind == scope.actionKind &&
      workingDirectory == scope.workingDirectory
  }

  private fun ApprovalRequestRecord.identity() =
    ApprovalRequestIdentity(approvalId, repositoryIdentity, runId, processGeneration, provider, providerRequestId)

  private companion object {
    const val STORED_REQUEST_COLUMNS =
      "record_json, action_class, write_capable, input_complete, " +
        "provider_disposition, delivery_state"
    const val IDENTITY_MATCH =
      "approval_id = ? AND repository_identity = ? AND run_id = ? AND " +
        "process_generation = ? AND provider = ? " +
        "AND provider_request_id_type = ? AND provider_request_id = ?"
    const val NOT_CLAIMED = "not_claimed"
  }
}
