package skillbill.infrastructure.sqlite

import skillbill.contracts.workflow.identity.blockincident.BLOCK_INCIDENT_CONTRACT_VERSION
import skillbill.error.core.BlockIncidentFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.blockincident.BlockIncidentContractValidator
import skillbill.ports.blockincident.BlockIncidentLookup
import skillbill.ports.blockincident.BlockIncidentRepository
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAliasKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentClaim
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentIdentityAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecovery
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentReport
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentState
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentTransitions
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentTransitionDecision
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException

internal class SqliteBlockIncidentRepository(
  private val connection: Connection,
  private val clock: Clock,
  private val contractValidator: BlockIncidentContractValidator,
  private val diagnostics: RuntimeDiagnostics? = null,
) : BlockIncidentRepository {
  override fun createOrLink(
    occurrence: BlockIncidentOccurrence,
    alias: BlockIncidentAlias,
    deadline: Instant,
  ): BlockIncident {
    val proposed =
      BlockIncident(
        occurrence = occurrence,
        aliases = listOf(alias),
        state =
          BlockIncidentState(
            diagnosis = BlockIncidentDiagnosis.PENDING,
            recovery = BlockIncidentRecovery.UNAVAILABLE,
            report = BlockIncidentReport.UNAVAILABLE,
            revision = 1,
            deadline = deadline,
            retryCount = 0,
            nextEligibleAt = null,
            claim = null,
          ),
      )
    if (!proposed.isValid()) throw incidentCorrupt("Proposed block incident violates its persistence contract.")
    proposed.requireContract()
    findByAlias(alias)?.let { existing ->
      if (!existing.occurrence.hasSamePersistedOriginalFailure(occurrence, compareObservedAt = false)) {
        throw SkillBillRuntimeException(
          BlockIncidentFailureCode.IMMUTABLE_CONFLICT,
          "Block incident alias already belongs to a different original failure.",
        )
      }
      return existing
    }
    connection.prepareStatement(
      """
      INSERT INTO block_incidents (
        occurrence_token, contract_version, workflow_id, parent_workflow_id, phase_id, phase_run_id,
        invocation_id, execution_id, attempt, generation, origin, reason, failure_code,
        failure_disposition, throwable_type, exit_code, observed_at, evidence_availability, deadline, exit_behavior,
        identity_availability, occurrence_json
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, occurrence.occurrenceToken)
      statement.setString(2, BLOCK_INCIDENT_CONTRACT_VERSION)
      statement.setString(3, occurrence.workflowId)
      statement.setString(4, occurrence.parentWorkflowId)
      statement.setString(5, occurrence.phaseId)
      statement.setString(6, occurrence.phaseRunId)
      statement.setString(7, occurrence.invocationId)
      statement.setString(8, occurrence.executionId)
      if (occurrence.attempt == null) {
        statement.setNull(9, java.sql.Types.INTEGER)
      } else {
        statement.setInt(9, occurrence.attempt)
      }
      statement.setLong(10, occurrence.generation)
      statement.setString(11, occurrence.origin.wireValue)
      statement.setString(12, occurrence.reason)
      statement.setString(13, occurrence.failureCode)
      statement.setString(14, occurrence.failureDisposition)
      statement.setString(15, occurrence.throwableType)
      if (occurrence.exitCode == null) {
        statement.setNull(16, java.sql.Types.INTEGER)
      } else {
        statement.setInt(16, occurrence.exitCode)
      }
      statement.setString(17, occurrence.observedAt.toString())
      statement.setString(18, occurrence.evidenceAvailability.wireValue)
      statement.setString(19, deadline.toString())
      statement.setString(20, occurrence.exitBehavior.wireValue)
      statement.setString(21, occurrence.identityAvailability.wireValue)
      statement.setString(22, SqliteBlockIncidentOccurrenceCodec.encode(occurrence))
      statement.executeUpdate()
    }
    connection.prepareStatement(
      "INSERT INTO block_incident_aliases(alias_kind, alias_value, occurrence_token) VALUES (?, ?, ?)",
    ).use { statement ->
      statement.setString(1, alias.kind.wireValue)
      statement.setString(2, alias.value)
      statement.setString(3, occurrence.occurrenceToken)
      statement.executeUpdate()
    }
    val persisted = load(occurrence.occurrenceToken)
      ?: throw incidentCorrupt("Inserted incident disappeared during validated readback.")
    if (persisted.occurrence != occurrence ||
      persisted.aliases != listOf(alias) ||
      persisted.state != proposed.state
    ) {
      throw incidentCorrupt("Inserted incident differs from its persisted candidate projection.")
    }
    return persisted
  }

  override fun find(occurrenceToken: String): BlockIncident? =
    when (val lookup = lookup(occurrenceToken)) {
      is BlockIncidentLookup.Found -> lookup.incident
      BlockIncidentLookup.Absent -> null
      is BlockIncidentLookup.Quarantined -> throw quarantined(lookup)
    }

  override fun lookup(occurrenceToken: String): BlockIncidentLookup =
    try {
      load(occurrenceToken)?.let(BlockIncidentLookup::Found) ?: BlockIncidentLookup.Absent
    } catch (failure: SkillBillRuntimeException) {
      if (failure.code != BlockIncidentFailureCode.INVALID_SCHEMA &&
        failure.code != BlockIncidentFailureCode.INVALID_VERSION
      ) throw failure
      quarantine(occurrenceToken, failure)
    }

  /** Strict read used for selectors and post-mutation readback, where a corrupt row must fail the caller. */
  private fun load(occurrenceToken: String): BlockIncident? {
    if (!validOccurrenceToken(occurrenceToken)) throw incidentCorrupt("Invalid block incident selector.")
    return connection.prepareStatement("SELECT * FROM block_incidents WHERE occurrence_token = ?").use { statement ->
      statement.setString(1, occurrenceToken)
      statement.executeQuery().use { rows -> if (rows.next()) rows.toIncident() else null }
    }
  }

  /**
   * Records a corrupt row once and keeps it out of later scans. Read-only sessions cannot write, so they report
   * the row as quarantine-pending and leave the durable write to the next writable pass.
   */
  private fun quarantine(occurrenceToken: String, failure: SkillBillRuntimeException): BlockIncidentLookup.Quarantined {
    val failureCode = (failure.code as BlockIncidentFailureCode).name
    val message = (failure.message ?: "Invalid block incident.").take(MAX_QUARANTINE_MESSAGE_CHARS)
    val durable = isQuarantined(occurrenceToken) || !connection.isReadOnly && try {
      connection.prepareStatement(
        "INSERT OR IGNORE INTO block_incident_quarantine(occurrence_token, failure_code, failure_message, " +
          "raw_row_digest, quarantined_at) SELECT occurrence_token, ?, ?, ?, ? FROM block_incidents " +
          "WHERE occurrence_token = ?",
      ).use { statement ->
        statement.setString(1, failureCode)
        statement.setString(2, message)
        statement.setString(3, rawRowDigest(occurrenceToken))
        statement.setString(4, clock.instant().toString())
        statement.setString(5, occurrenceToken)
        statement.executeUpdate()
      }
      isQuarantined(occurrenceToken)
    } catch (writeFailure: SQLException) {
      diagnostics?.warning("Block incident $occurrenceToken could not be quarantined.", writeFailure)
      false
    }
    diagnostics?.warning(
      "Block incident $occurrenceToken failed validation ($failureCode)" +
        if (durable) " and is quarantined." else "; quarantine is pending a writable session.",
    )
    return BlockIncidentLookup.Quarantined(occurrenceToken, failureCode, durable)
  }

  private fun isQuarantined(occurrenceToken: String): Boolean =
    connection.prepareStatement("SELECT 1 FROM block_incident_quarantine WHERE occurrence_token = ?").use { statement ->
      statement.setString(1, occurrenceToken)
      statement.executeQuery().use { rows -> rows.next() }
    }

  private fun rawRowDigest(occurrenceToken: String): String {
    val raw = connection.prepareStatement(
      "SELECT coalesce(observed_at, ''), coalesce(occurrence_json, '') FROM block_incidents WHERE occurrence_token = ?",
    ).use { statement ->
      statement.setString(1, occurrenceToken)
      statement.executeQuery().use { rows -> if (rows.next()) "${rows.getString(1)}:${rows.getString(2)}" else "" }
    }
    return sha256("$occurrenceToken:$raw")
  }

  private fun quarantined(lookup: BlockIncidentLookup.Quarantined): SkillBillRuntimeException =
    SkillBillRuntimeException(
      BlockIncidentFailureCode.QUARANTINE,
      "Block incident ${lookup.occurrenceToken} is quarantined after failing validation (${lookup.failureCode}).",
    )

  override fun pending(limit: Int): List<BlockIncident> {
    if (limit !in 1..MAX_PENDING_BATCH) throw incidentCorrupt("Block incident pending limit is outside its contract.")
    val now = clock.instant()
    val candidates = listOf(BlockIncidentDiagnosis.PENDING, BlockIncidentDiagnosis.INVESTIGATING)
      .flatMap { diagnosis -> scanCandidates(diagnosis, now) }
      .sortedWith(compareBy({ it.occurrence.observedAt }, { it.occurrence.occurrenceToken }))
    return candidates.take(limit)
  }

  /**
   * Scans one bounded keyset page per call and persists where it stopped, so rows that stay ineligible or corrupt
   * cannot starve later rows. A page shorter than the window ends the cycle and the next call rewinds.
   */
  private fun scanCandidates(diagnosis: BlockIncidentDiagnosis, now: Instant): List<BlockIncident> {
    val cursor = scanCursor(diagnosis)
    val scanned = connection.prepareStatement(
      "SELECT occurrence_token, observed_at FROM block_incidents " +
        "WHERE contract_version = ? AND diagnosis = ? AND (? IS NULL OR observed_at > ? OR " +
        "(observed_at = ? AND occurrence_token > ?)) AND occurrence_token NOT IN " +
        "(SELECT occurrence_token FROM block_incident_quarantine) " +
        "ORDER BY observed_at, occurrence_token LIMIT ?",
    ).use { statement ->
      statement.setString(1, BLOCK_INCIDENT_CONTRACT_VERSION)
      statement.setString(2, diagnosis.wireValue)
      statement.setString(3, cursor?.first)
      statement.setString(4, cursor?.first)
      statement.setString(5, cursor?.first)
      statement.setString(6, cursor?.second)
      statement.setInt(7, MAX_PENDING_SCAN)
      statement.executeQuery().use { rows ->
        buildList { while (rows.next()) add(ScannedRow(rows.getString(1), rows.getString(2))) }
      }
    }
    advanceScanCursor(diagnosis, scanned.lastOrNull()?.takeIf { scanned.size == MAX_PENDING_SCAN })
    return scanned.mapNotNull { row ->
      val incident = (lookup(row.occurrenceToken) as? BlockIncidentLookup.Found)?.incident
        ?: return@mapNotNull null
      val state = incident.state
      incident.takeIf {
        state.deadline <= now ||
          diagnosis == BlockIncidentDiagnosis.PENDING && state.nextEligibleAt?.isAfter(now) != true
      }
    }
  }

  private fun scanCursor(diagnosis: BlockIncidentDiagnosis): Pair<String, String>? =
    connection.prepareStatement(
      "SELECT observed_at, occurrence_token FROM block_incident_scan_cursors WHERE diagnosis = ?",
    ).use { statement ->
      statement.setString(1, diagnosis.wireValue)
      statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null }
    }

  private fun advanceScanCursor(diagnosis: BlockIncidentDiagnosis, last: ScannedRow?) {
    if (last == null) {
      connection.prepareStatement("DELETE FROM block_incident_scan_cursors WHERE diagnosis = ?").use { statement ->
        statement.setString(1, diagnosis.wireValue)
        statement.executeUpdate()
      }
      return
    }
    connection.prepareStatement(
      "INSERT INTO block_incident_scan_cursors(diagnosis, observed_at, occurrence_token) VALUES (?, ?, ?) " +
        "ON CONFLICT(diagnosis) DO UPDATE SET observed_at = excluded.observed_at, " +
        "occurrence_token = excluded.occurrence_token",
    ).use { statement ->
      statement.setString(1, diagnosis.wireValue)
      statement.setString(2, last.observedAt)
      statement.setString(3, last.occurrenceToken)
      statement.executeUpdate()
    }
  }

  private class ScannedRow(val occurrenceToken: String, val observedAt: String)

  private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { byte ->
      "%02x".format(byte.toInt() and 0xff)
    }

  override fun claim(
    occurrenceToken: String,
    expectedRevision: Long,
    ownerToken: String,
    ownerGeneration: Long,
    hostIdentity: String,
    processId: Long,
    expiresAt: Instant,
  ): Boolean {
    if (expectedRevision <= 0 || ownerGeneration <= 0 || processId <= 0 ||
      !validOccurrenceToken(occurrenceToken) ||
      ownerToken.isBlank() || ownerToken.length > MAX_IDENTITY_CHARS ||
      hostIdentity.isBlank() || hostIdentity.length > MAX_IDENTITY_CHARS
    ) throw incidentCorrupt("Block incident claim input violates its persistence contract.")
    val current = (lookup(occurrenceToken) as? BlockIncidentLookup.Found)?.incident ?: return false
    val nowInstant = clock.instant()
    val claim = BlockIncidentClaim(ownerToken, ownerGeneration, hostIdentity, processId, expiresAt)
    val candidate = when (val decision = BlockIncidentTransitions.claim(current, nowInstant, expectedRevision, claim)) {
      is BlockIncidentTransitionDecision.Accepted -> decision.candidate
      is BlockIncidentTransitionDecision.Refused -> return false
    }
    candidate.requireContract()
    val claimed = connection.prepareStatement(
      """
      UPDATE block_incidents SET diagnosis = ?, revision = revision + 1, next_eligible_at = NULL, owner_token = ?,
        owner_generation = ?, host_identity = ?, process_id = ?, lease_expires_at = ?
      WHERE occurrence_token = ? AND revision = ? AND diagnosis = ? AND owner_token IS NULL
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, BlockIncidentDiagnosis.INVESTIGATING.wireValue)
      statement.setString(2, ownerToken)
      statement.setLong(3, ownerGeneration)
      statement.setString(4, hostIdentity)
      statement.setLong(5, processId)
      statement.setString(6, expiresAt.toString())
      statement.setString(7, occurrenceToken)
      statement.setLong(8, expectedRevision)
      statement.setString(9, BlockIncidentDiagnosis.PENDING.wireValue)
      statement.executeUpdate() == 1
    }
    if (!claimed) return false
    val persisted = load(occurrenceToken) ?: throw incidentCorrupt("Claimed incident disappeared during readback.")
    if (persisted != candidate) throw incidentCorrupt("Claimed incident differs from its validated candidate.")
    return true
  }

  override fun renew(
    occurrenceToken: String,
    ownerToken: String,
    ownerGeneration: Long,
    expiresAt: Instant,
  ): Boolean =
    run {
      val nowInstant = clock.instant()
      if (!validOccurrenceToken(occurrenceToken) || ownerToken.isBlank() ||
        ownerToken.length > MAX_IDENTITY_CHARS || ownerGeneration <= 0
      ) {
        throw incidentCorrupt("Block incident renewal input violates its persistence contract.")
      }
      val current = (lookup(occurrenceToken) as? BlockIncidentLookup.Found)?.incident ?: return@run false
      val candidate = when (
        val decision = BlockIncidentTransitions.renew(current, nowInstant, ownerToken, ownerGeneration, expiresAt)
      ) {
        is BlockIncidentTransitionDecision.Accepted -> decision.candidate
        is BlockIncidentTransitionDecision.Refused -> return@run false
      }
      candidate.requireContract()
      val renewed = connection.prepareStatement(
        "UPDATE block_incidents SET lease_expires_at = ?, revision = revision + 1 " +
          "WHERE occurrence_token = ? AND revision = ? AND diagnosis = ? AND owner_token = ? AND owner_generation = ?",
      ).use { statement ->
        statement.setString(1, expiresAt.toString())
        statement.setString(2, occurrenceToken)
        statement.setLong(3, current.state.revision)
        statement.setString(4, BlockIncidentDiagnosis.INVESTIGATING.wireValue)
        statement.setString(5, ownerToken)
        statement.setLong(6, ownerGeneration)
        statement.executeUpdate() == 1
      }
      if (!renewed) return@run false
      val persisted = load(occurrenceToken) ?: throw incidentCorrupt("Renewed incident disappeared during readback.")
      if (persisted != candidate) throw incidentCorrupt("Renewed incident differs from its validated candidate.")
      true
    }

  override fun settle(
    occurrenceToken: String,
    ownerToken: String,
    ownerGeneration: Long,
    diagnosis: BlockIncidentDiagnosis,
    nextEligibleAt: Instant?,
  ): Boolean {
    if (!validOccurrenceToken(occurrenceToken) || diagnosis == BlockIncidentDiagnosis.INVESTIGATING ||
      ownerToken.isBlank() ||
      ownerToken.length > MAX_IDENTITY_CHARS || ownerGeneration <= 0 ||
      (diagnosis == BlockIncidentDiagnosis.PENDING && nextEligibleAt == null) ||
      (diagnosis != BlockIncidentDiagnosis.PENDING && nextEligibleAt != null)
    ) throw incidentCorrupt("Block incident settlement input violates its lifecycle contract.")
    val current = (lookup(occurrenceToken) as? BlockIncidentLookup.Found)?.incident ?: return false
    val now = clock.instant()
    val candidate = when (
      val decision = BlockIncidentTransitions.settle(
        current, now, ownerToken, ownerGeneration, diagnosis, nextEligibleAt,
      )
    ) {
      is BlockIncidentTransitionDecision.Accepted -> decision.candidate
      is BlockIncidentTransitionDecision.Refused -> return false
    }
    candidate.requireContract()
    val settled = connection.prepareStatement(
      "UPDATE block_incidents SET diagnosis = ?, next_eligible_at = ?, retry_count = retry_count + 1, " +
        "revision = revision + 1, owner_token = NULL, owner_generation = NULL, host_identity = NULL, " +
        "process_id = NULL, lease_expires_at = NULL WHERE occurrence_token = ? AND revision = ? AND diagnosis = ? " +
        "AND owner_token = ? AND owner_generation = ?",
    ).use { statement ->
      statement.setString(1, diagnosis.wireValue)
      statement.setString(2, nextEligibleAt?.toString())
      statement.setString(3, occurrenceToken)
      statement.setLong(4, current.state.revision)
      statement.setString(5, BlockIncidentDiagnosis.INVESTIGATING.wireValue)
      statement.setString(6, ownerToken)
      statement.setLong(7, ownerGeneration)
      statement.executeUpdate() == 1
    }
    if (!settled) return false
    val persisted = load(occurrenceToken) ?: throw incidentCorrupt("Settled incident disappeared during readback.")
    if (persisted != candidate) throw incidentCorrupt("Settled incident differs from its validated candidate.")
    return true
  }

  override fun expireDeadline(
    occurrenceToken: String,
    expectedRevision: Long,
    observedOwnerToken: String?,
    observedOwnerGeneration: Long?,
  ): Boolean {
    if (!validOccurrenceToken(occurrenceToken) || expectedRevision <= 0 ||
      (observedOwnerToken == null) != (observedOwnerGeneration == null)
    ) throw incidentCorrupt("Block incident expiry input violates its persistence contract.")
    val current = (lookup(occurrenceToken) as? BlockIncidentLookup.Found)?.incident ?: return false
    val now = clock.instant()
    val candidate = when (
      val decision = BlockIncidentTransitions.expireDeadline(
        current, now, expectedRevision, observedOwnerToken, observedOwnerGeneration,
      )
    ) {
      is BlockIncidentTransitionDecision.Accepted -> decision.candidate
      is BlockIncidentTransitionDecision.Refused -> return false
    }
    candidate.requireContract()
    val expired = connection.prepareStatement(
      "UPDATE block_incidents SET diagnosis = ?, revision = revision + 1, next_eligible_at = NULL, " +
        "retry_count = retry_count + ?, owner_token = NULL, owner_generation = NULL, host_identity = NULL, " +
        "process_id = NULL, lease_expires_at = NULL WHERE occurrence_token = ? AND revision = ? " +
        "AND diagnosis = ? AND owner_token IS ? AND owner_generation IS ?",
    ).use { statement ->
      statement.setString(1, BlockIncidentDiagnosis.TIMED_OUT.wireValue)
      statement.setInt(2, if (current.state.claim == null) 0 else 1)
      statement.setString(3, occurrenceToken)
      statement.setLong(4, expectedRevision)
      statement.setString(5, current.state.diagnosis.wireValue)
      statement.setString(6, observedOwnerToken)
      if (observedOwnerGeneration == null) statement.setNull(7, java.sql.Types.BIGINT)
      else statement.setLong(7, observedOwnerGeneration)
      statement.executeUpdate() == 1
    }
    if (!expired) return false
    val persisted = load(occurrenceToken) ?: throw incidentCorrupt("Expired incident disappeared during readback.")
    if (persisted != candidate) throw incidentCorrupt("Expired incident differs from its validated candidate.")
    return true
  }

  private fun validOccurrenceToken(value: String): Boolean = OCCURRENCE_TOKEN_PATTERN.matches(value)

  private fun BlockIncidentOccurrence.hasSamePersistedOriginalFailure(
    proposed: BlockIncidentOccurrence,
    compareObservedAt: Boolean,
  ): Boolean =
    occurrenceToken == proposed.occurrenceToken &&
      workflowId == proposed.workflowId && parentWorkflowId == proposed.parentWorkflowId &&
      phaseId == proposed.phaseId && phaseRunId == proposed.phaseRunId &&
      invocationId == proposed.invocationId && executionId == proposed.executionId && attempt == proposed.attempt &&
      generation == proposed.generation && origin == proposed.origin && reason == proposed.reason &&
      failureCode == proposed.failureCode && failureDisposition == proposed.failureDisposition &&
      throwableType == proposed.throwableType && exitCode == proposed.exitCode &&
      evidenceAvailability == proposed.evidenceAvailability &&
      identityAvailability == proposed.identityAvailability &&
      exitBehavior == proposed.exitBehavior &&
      (!compareObservedAt || observedAt == proposed.observedAt)

  private fun findByAlias(alias: BlockIncidentAlias): BlockIncident? {
    if (alias.value.isBlank() || alias.value.length > MAX_ALIAS_CHARS) {
      throw incidentCorrupt("Invalid block incident alias selector.")
    }
    return connection.prepareStatement(
      "SELECT occurrence_token FROM block_incident_aliases WHERE alias_kind = ? AND alias_value = ?",
    ).use { statement ->
      statement.setString(1, alias.kind.wireValue)
      statement.setString(2, alias.value)
      statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }?.let { occurrenceToken ->
      when (val lookup = lookup(occurrenceToken)) {
        is BlockIncidentLookup.Found -> lookup.incident
        BlockIncidentLookup.Absent -> null
        is BlockIncidentLookup.Quarantined -> throw quarantined(lookup)
      }
    }
  }

  private fun ResultSet.toIncident(): BlockIncident {
    if (requiredText("contract_version") != BLOCK_INCIDENT_CONTRACT_VERSION) {
      throw SkillBillRuntimeException(
        BlockIncidentFailureCode.INVALID_VERSION,
        "Stored block incident uses an unsupported contract version.",
      )
    }
    val token = requiredText("occurrence_token")
    val aliases =
      connection.prepareStatement(
        "SELECT alias_kind, alias_value FROM block_incident_aliases WHERE occurrence_token = ?",
      ).use { statement ->
        statement.setString(1, token)
        statement.executeQuery().use { rows ->
          buildList {
            while (rows.next()) {
              add(
                BlockIncidentAlias(
                  rows.getString("alias_kind").decodeEnum(BlockIncidentAliasKind.entries) { it.wireValue },
                  rows.getString("alias_value"),
                ),
              )
            }
          }
        }
      }
    val owner = getString("owner_token")
    if (owner == null && listOf("owner_generation", "host_identity", "process_id", "lease_expires_at").any { column ->
        when (column) {
          "owner_generation", "process_id" -> getObject(column) != null
          else -> getString(column) != null
        }
      }
    ) throw incidentCorrupt("Block incident has lease fields without an owner token.")
    val claim = owner?.let { ownerToken ->
      val ownerGeneration = getLong("owner_generation").takeUnless { wasNull() }
        ?: throw incidentCorrupt("Block incident owner generation is absent.")
      val hostIdentity = getString("host_identity")
        ?: throw incidentCorrupt("Block incident host identity is absent.")
      val processId = getLong("process_id").takeUnless { wasNull() }
        ?: throw incidentCorrupt("Block incident process identity is absent.")
      val leaseExpiresAt = getString("lease_expires_at")
        ?: throw incidentCorrupt("Block incident lease expiry is absent.")
      BlockIncidentClaim(
        ownerToken = ownerToken,
        ownerGeneration = ownerGeneration,
        hostIdentity = hostIdentity,
        processId = processId,
        expiresAt = leaseExpiresAt.toInstant("lease_expires_at"),
      )
    }
    val columnOccurrence =
      BlockIncidentOccurrence(
        occurrenceToken = token,
        workflowId = getString("workflow_id"),
        parentWorkflowId = getString("parent_workflow_id"),
        phaseId = getString("phase_id"),
        phaseRunId = getString("phase_run_id"),
        invocationId = getString("invocation_id"),
        executionId = getString("execution_id"),
        attempt = getInt("attempt").takeUnless { wasNull() },
        generation = requiredLong("generation"),
        origin = requiredText("origin").decodeEnum(BlockIncidentOrigin.entries) { it.wireValue },
        reason = requiredText("reason"),
        failureCode = getString("failure_code"),
        failureDisposition = getString("failure_disposition"),
        throwableType = getString("throwable_type"),
        exitCode = getInt("exit_code").takeUnless { wasNull() },
        observedAt = requiredText("observed_at").toInstant("observed_at"),
        evidenceAvailability =
          requiredText("evidence_availability").decodeEnum(BlockIncidentEvidenceAvailability.entries) {
            it.wireValue
          },
        exitBehavior = requiredText("exit_behavior").decodeEnum(BlockIncidentExitBehavior.entries) {
          it.wireValue
        },
        identityAvailability = requiredText("identity_availability")
          .decodeEnum(BlockIncidentIdentityAvailability.entries) { it.wireValue },
      )
    val occurrence = getString("occurrence_json")?.let { raw ->
      SqliteBlockIncidentOccurrenceCodec.decode(raw).also { stored ->
        if (!stored.hasSamePersistedOriginalFailure(columnOccurrence, compareObservedAt = true)) {
          throw incidentCorrupt("Stored block incident occurrence differs from its indexed columns.")
        }
      }
    } ?: columnOccurrence
    val incident = BlockIncident(
      occurrence = occurrence,
      aliases = aliases,
      state =
        BlockIncidentState(
          diagnosis = requiredText("diagnosis").decodeEnum(BlockIncidentDiagnosis.entries) { it.wireValue },
          recovery = requiredText("recovery").decodeEnum(BlockIncidentRecovery.entries) { it.wireValue },
          report = requiredText("report").decodeEnum(BlockIncidentReport.entries) { it.wireValue },
          revision = requiredLong("revision"),
          deadline = requiredText("deadline").toInstant("deadline"),
          retryCount = requiredInt("retry_count"),
          nextEligibleAt = getString("next_eligible_at")?.toInstant("next_eligible_at"),
          claim = claim,
        ),
    )
    if (!incident.isValid()) throw incidentCorrupt("Stored block incident violates its lifecycle contract.")
    incident.requireContract()
    return incident
  }

  private fun <T> String.decodeEnum(
    values: List<T>,
    wireValue: (T) -> String,
  ): T = values.firstOrNull { wireValue(it) == this } ?: throw incidentCorrupt("Unsupported block incident value.")

  private fun ResultSet.requiredText(column: String): String =
    getString(column) ?: throw incidentCorrupt("Block incident $column is absent.")

  private fun ResultSet.requiredLong(column: String): Long =
    getLong(column).takeUnless { wasNull() } ?: throw incidentCorrupt("Block incident $column is absent.")

  private fun ResultSet.requiredInt(column: String): Int =
    getInt(column).takeUnless { wasNull() } ?: throw incidentCorrupt("Block incident $column is absent.")

  private fun String.toInstant(field: String): Instant =
    try {
      Instant.parse(this)
    } catch (failure: DateTimeParseException) {
      throw incidentCorrupt("Invalid block incident $field.", failure)
    }

  /** Governed schema gate: every proposed row, transition candidate and decoded row must satisfy contract 0.2. */
  private fun BlockIncident.requireContract() {
    contractValidator.validate(SqliteBlockIncidentOccurrenceCodec.encodeIncident(this))
  }

  private fun incidentCorrupt(message: String, cause: Throwable? = null) =
    SkillBillRuntimeException(BlockIncidentFailureCode.INVALID_SCHEMA, message, cause)

  companion object {
    private const val MAX_PENDING_BATCH = 64
    private const val MAX_PENDING_SCAN = 256
    private const val MAX_IDENTITY_CHARS = 512
    private const val MAX_QUARANTINE_MESSAGE_CHARS = 1024
    private const val MAX_ALIAS_CHARS = 512
    private val OCCURRENCE_TOKEN_PATTERN = Regex("bi_[0-9a-f]{64}")
  }
}
