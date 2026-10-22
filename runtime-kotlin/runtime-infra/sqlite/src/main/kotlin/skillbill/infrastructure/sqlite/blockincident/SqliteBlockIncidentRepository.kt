package skillbill.infrastructure.sqlite.blockincident

import skillbill.contracts.workflow.identity.blockincident.BLOCK_INCIDENT_CONTRACT_VERSION
import skillbill.error.blockincident.BlockIncidentFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.blockincident.BlockIncidentContractValidator
import skillbill.ports.blockincident.BlockIncidentRepository
import skillbill.ports.blockincident.model.BlockIncidentLookup
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAliasKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentClaim
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisRecord
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOwner
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecovery
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentReport
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentState
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentTransitionDecision
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentTransitions
import skillbill.workflow.taskruntime.model.blockincident.validation
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.util.HexFormat

internal class SqliteBlockIncidentRepository(
  private val connection: Connection,
  private val clock: Clock,
  private val contractValidator: BlockIncidentContractValidator,
  private val diagnostics: RuntimeDiagnostics? = null,
) : BlockIncidentRepository {
  private val rolloutQueries = SqliteBlockIncidentRolloutQueries(connection)
  private val diagnosisStore = SqliteBlockIncidentDiagnosisStore(connection, clock, diagnostics)

  override fun rolloutActivatedAt(): Instant? = rolloutQueries.activatedAt()

  override fun blockedWithoutIncident(
    since: Instant,
    limit: Int,
  ) = rolloutQueries.blockedWithoutIncident(since, limit)

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
      existing.requireSameOriginalFailure(
        occurrence,
        "Block incident alias already belongs to a different original failure.",
      )
      return existing
    }
    load(occurrence.occurrenceToken)?.let { existing ->
      existing.requireSameOriginalFailure(
        occurrence,
        "Block incident token already belongs to a different original failure.",
      )
      persistAlias(alias, occurrence.occurrenceToken)
      return existing.copy(aliases = existing.aliases + alias)
    }
    insertIncident(occurrence, deadline)
    persistAlias(alias, occurrence.occurrenceToken)
    val persisted =
      load(occurrence.occurrenceToken)
        ?: throw incidentCorrupt("Inserted incident disappeared during validated readback.")
    if (persisted.occurrence != occurrence ||
      persisted.aliases != listOf(alias) ||
      persisted.state != proposed.state
    ) {
      throw incidentCorrupt("Inserted incident differs from its persisted candidate projection.")
    }
    return persisted
  }

  private fun BlockIncident.requireSameOriginalFailure(
    proposed: BlockIncidentOccurrence,
    message: String,
  ) {
    if (!occurrence.hasSamePersistedOriginalFailure(proposed, compareObservedAt = false)) {
      throw SkillBillRuntimeException(BlockIncidentFailureCode.IMMUTABLE_CONFLICT, message)
    }
  }

  private fun insertIncident(
    occurrence: BlockIncidentOccurrence,
    deadline: Instant,
  ) {
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
      statement.bindAll(
        occurrence.occurrenceToken,
        BLOCK_INCIDENT_CONTRACT_VERSION,
        occurrence.workflowId,
        occurrence.parentWorkflowId,
        occurrence.phaseId,
        occurrence.phaseRunId,
        occurrence.invocationId,
        occurrence.executionId,
        occurrence.attempt,
        occurrence.generation,
        occurrence.origin.wireValue,
        occurrence.reason,
        occurrence.failureCode,
        occurrence.failureDisposition,
        occurrence.throwableType,
        occurrence.exitCode,
        occurrence.observedAt.toString(),
        occurrence.evidenceAvailability.wireValue,
        deadline.toString(),
        occurrence.exitBehavior.wireValue,
        occurrence.identityAvailability.wireValue,
        SqliteBlockIncidentOccurrenceCodec.encode(occurrence),
      )
      statement.executeUpdate()
    }
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
      ) {
        throw failure
      }
      quarantine(occurrenceToken, failure)
    }

  private fun load(occurrenceToken: String): BlockIncident? {
    if (!validOccurrenceToken(occurrenceToken)) throw incidentCorrupt("Invalid block incident selector.")
    return connection.prepareStatement("SELECT * FROM block_incidents WHERE occurrence_token = ?").use { statement ->
      statement.setString(1, occurrenceToken)
      statement.executeQuery().use { rows -> if (rows.next()) rows.toIncident() else null }
    }
  }

  private fun quarantine(
    occurrenceToken: String,
    failure: SkillBillRuntimeException,
  ): BlockIncidentLookup.Quarantined {
    val failureCode = (failure.code as BlockIncidentFailureCode).name
    val message = (failure.message ?: "Invalid block incident.").take(MAX_QUARANTINE_MESSAGE_CHARS)
    val durable =
      isQuarantined(occurrenceToken) || !connection.isReadOnly &&
        try {
          connection.prepareStatement(
            "INSERT OR IGNORE INTO block_incident_quarantine(occurrence_token, failure_code, failure_message, " +
              "raw_row_digest, quarantined_at) SELECT occurrence_token, ?, ?, ?, ? FROM block_incidents " +
              "WHERE occurrence_token = ?",
          ).use { statement ->
            statement.bindAll(
              failureCode,
              message,
              rawRowDigest(occurrenceToken),
              clock.instant().toString(),
              occurrenceToken,
            )
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
    val raw =
      connection.prepareStatement(
        "SELECT coalesce(observed_at, ''), coalesce(occurrence_json, '') FROM block_incidents " +
          "WHERE occurrence_token = ?",
      ).use { statement ->
        statement.setString(1, occurrenceToken)
        statement.executeQuery().use { rows ->
          if (rows.next()) "${rows.getString(1)}:${rows.getString(2)}" else ""
        }
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
    val activatedAt = rolloutQueries.activatedAt() ?: return emptyList()
    val candidates =
      listOf(BlockIncidentDiagnosis.PENDING, BlockIncidentDiagnosis.INVESTIGATING)
        .flatMap { diagnosis -> scanCandidates(diagnosis, now, activatedAt) }
        .sortedWith(compareBy({ it.occurrence.observedAt }, { it.occurrence.occurrenceToken }))
    return candidates.take(limit)
  }

  private fun scanCandidates(
    diagnosis: BlockIncidentDiagnosis,
    now: Instant,
    activatedAt: Instant,
  ): List<BlockIncident> {
    val cursor = scanCursor(diagnosis)
    val scanned =
      connection.prepareStatement(
        "SELECT occurrence_token, observed_at FROM block_incidents " +
          "WHERE contract_version = ? AND diagnosis = ? AND (? IS NULL OR observed_at > ? OR " +
          "(observed_at = ? AND occurrence_token > ?)) AND occurrence_token NOT IN " +
          "(SELECT occurrence_token FROM block_incident_quarantine) " +
          "ORDER BY observed_at, occurrence_token LIMIT ?",
      ).use { statement ->
        statement.bindAll(
          BLOCK_INCIDENT_CONTRACT_VERSION,
          diagnosis.wireValue,
          cursor?.first,
          cursor?.first,
          cursor?.first,
          cursor?.second,
          MAX_PENDING_SCAN,
        )
        statement.executeQuery().use { rows ->
          buildList { while (rows.next()) add(ScannedRow(rows.getString(1), rows.getString(2))) }
        }
      }
    advanceScanCursor(diagnosis, scanned.lastOrNull()?.takeIf { scanned.size == MAX_PENDING_SCAN })
    return scanned.mapNotNull { row ->
      val incident =
        (lookup(row.occurrenceToken) as? BlockIncidentLookup.Found)?.incident
          ?: return@mapNotNull null
      val state = incident.state
      incident.takeIf {
        incident.occurrence.observedAt >= activatedAt && (
          state.deadline <= now ||
            diagnosis == BlockIncidentDiagnosis.PENDING && state.nextEligibleAt?.isAfter(now) != true ||
            diagnosis == BlockIncidentDiagnosis.INVESTIGATING && state.claim?.expiresAt?.let { it <= now } == true
        )
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

  private fun advanceScanCursor(
    diagnosis: BlockIncidentDiagnosis,
    last: ScannedRow?,
  ) {
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
      statement.bindAll(diagnosis.wireValue, last.observedAt, last.occurrenceToken)
      statement.executeUpdate()
    }
  }

  private class ScannedRow(val occurrenceToken: String, val observedAt: String)

  private fun sha256(text: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

  override fun claim(
    occurrenceToken: String,
    expectedRevision: Long,
    claim: BlockIncidentClaim,
  ): Boolean {
    requireValidClaim(occurrenceToken, expectedRevision, claim)
    val current = (lookup(occurrenceToken) as? BlockIncidentLookup.Found)?.incident ?: return false
    val nowInstant = clock.instant()
    val candidate =
      when (val decision = BlockIncidentTransitions.claim(current, nowInstant, expectedRevision, claim)) {
        is BlockIncidentTransitionDecision.Accepted -> decision.candidate
        is BlockIncidentTransitionDecision.Refused -> return false
      }
    candidate.requireContract()
    val claimed =
      connection.prepareStatement(
        """
        UPDATE block_incidents SET diagnosis = ?, revision = revision + 1, next_eligible_at = NULL, owner_token = ?,
          owner_generation = ?, host_identity = ?, process_id = ?, lease_expires_at = ?, deadline = ?, retry_count = ?
        WHERE occurrence_token = ? AND revision = ? AND (
          (diagnosis = ? AND owner_token IS NULL) OR
          (diagnosis = ? AND lease_expires_at <= ? AND owner_token = ? AND owner_generation = ?)
        )
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(
          BlockIncidentDiagnosis.INVESTIGATING.wireValue,
          claim.ownerToken,
          claim.ownerGeneration,
          claim.hostIdentity,
          claim.processId,
          claim.expiresAt.toString(),
          candidate.state.deadline.toString(),
          candidate.state.retryCount,
          occurrenceToken,
          expectedRevision,
          BlockIncidentDiagnosis.PENDING.wireValue,
          BlockIncidentDiagnosis.INVESTIGATING.wireValue,
          nowInstant.toString(),
          current.state.claim?.ownerToken,
          current.state.claim?.ownerGeneration ?: 0L,
        )
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
      if (!validOccurrenceToken(occurrenceToken) || !validIdentity(ownerToken) || ownerGeneration <= 0) {
        throw incidentCorrupt("Block incident renewal input violates its persistence contract.")
      }
      val current = (lookup(occurrenceToken) as? BlockIncidentLookup.Found)?.incident ?: return@run false
      val candidate =
        when (
          val decision =
            BlockIncidentTransitions.renew(
              current,
              nowInstant,
              BlockIncidentOwner(ownerToken, ownerGeneration),
              expiresAt,
            )
        ) {
          is BlockIncidentTransitionDecision.Accepted -> decision.candidate
          is BlockIncidentTransitionDecision.Refused -> return@run false
        }
      candidate.requireContract()
      val renewed =
        connection.prepareStatement(
          "UPDATE block_incidents SET lease_expires_at = ?, revision = revision + 1 " +
            "WHERE occurrence_token = ? AND revision = ? AND diagnosis = ? AND owner_token = ? " +
            "AND owner_generation = ?",
        ).use { statement ->
          statement.bindAll(
            expiresAt.toString(),
            occurrenceToken,
            current.state.revision,
            BlockIncidentDiagnosis.INVESTIGATING.wireValue,
            ownerToken,
            ownerGeneration,
          )
          statement.executeUpdate() == 1
        }
      if (!renewed) return@run false
      val persisted =
        load(occurrenceToken) ?: throw incidentCorrupt("Renewed incident disappeared during readback.")
      if (persisted != candidate) throw incidentCorrupt("Renewed incident differs from its validated candidate.")
      true
    }

  override fun settle(
    occurrenceToken: String,
    ownerToken: String,
    ownerGeneration: Long,
    diagnosis: BlockIncidentDiagnosis,
    nextEligibleAt: Instant?,
  ): Boolean =
    settleRecording(
      occurrenceToken,
      BlockIncidentOwner(ownerToken, ownerGeneration),
      diagnosis,
      null,
      nextEligibleAt,
    )

  override fun settleWithDiagnosis(
    ownerToken: String,
    ownerGeneration: Long,
    diagnosis: BlockIncidentDiagnosis,
    record: BlockIncidentDiagnosisRecord,
    nextEligibleAt: Instant?,
  ): Boolean =
    settleRecording(
      record.occurrenceToken,
      BlockIncidentOwner(ownerToken, ownerGeneration),
      diagnosis,
      record,
      nextEligibleAt,
    )

  override fun diagnoses(occurrenceToken: String): List<BlockIncidentDiagnosisRecord> {
    if (!validOccurrenceToken(occurrenceToken)) throw incidentCorrupt("Invalid block incident selector.")
    return diagnosisStore.read(occurrenceToken)
  }

  private fun settleRecording(
    occurrenceToken: String,
    owner: BlockIncidentOwner,
    diagnosis: BlockIncidentDiagnosis,
    record: BlockIncidentDiagnosisRecord?,
    nextEligibleAt: Instant?,
  ): Boolean {
    validateSettlement(occurrenceToken, owner, diagnosis, nextEligibleAt)
    val current = (lookup(occurrenceToken) as? BlockIncidentLookup.Found)?.incident ?: return false
    val now = clock.instant()
    val candidate =
      when (
        val decision =
          BlockIncidentTransitions.settle(
            current,
            now,
            owner,
            diagnosis,
            nextEligibleAt,
          )
      ) {
        is BlockIncidentTransitionDecision.Accepted -> decision.candidate
        is BlockIncidentTransitionDecision.Refused -> return false
      }
    candidate.requireContract()
    val stamped = record?.copy(revision = candidate.state.revision)
    if (stamped != null && (stamped.occurrenceToken != occurrenceToken || stamped.validation().isNotEmpty())) {
      throw incidentCorrupt("Block incident diagnosis violates its record contract.")
    }
    val settled =
      connection.prepareStatement(
        "UPDATE block_incidents SET diagnosis = ?, next_eligible_at = ?, retry_count = retry_count + 1, " +
          "revision = revision + 1, owner_token = NULL, owner_generation = NULL, host_identity = NULL, " +
          "process_id = NULL, lease_expires_at = NULL WHERE occurrence_token = ? AND revision = ? AND diagnosis = ? " +
          "AND owner_token = ? AND owner_generation = ?",
      ).use { statement ->
        statement.bindAll(
          diagnosis.wireValue,
          nextEligibleAt?.toString(),
          occurrenceToken,
          current.state.revision,
          BlockIncidentDiagnosis.INVESTIGATING.wireValue,
          owner.ownerToken,
          owner.ownerGeneration,
        )
        statement.executeUpdate() == 1
      }
    if (!settled) return false
    validateSettledReadback(occurrenceToken, candidate)
    stamped?.let(diagnosisStore::insert)
    return true
  }

  private fun validateSettlement(
    occurrenceToken: String,
    owner: BlockIncidentOwner,
    diagnosis: BlockIncidentDiagnosis,
    nextEligibleAt: Instant?,
  ) {
    val validOwner = validIdentity(owner.ownerToken) && owner.ownerGeneration > 0
    val validDiagnosis =
      diagnosis != BlockIncidentDiagnosis.INVESTIGATING &&
        (diagnosis == BlockIncidentDiagnosis.PENDING) == (nextEligibleAt != null)
    if (!validOccurrenceToken(occurrenceToken) || !validOwner || !validDiagnosis) {
      throw incidentCorrupt("Block incident settlement input violates its lifecycle contract.")
    }
  }

  private fun validateSettledReadback(
    occurrenceToken: String,
    candidate: BlockIncident,
  ) {
    val persisted = load(occurrenceToken) ?: throw incidentCorrupt("Settled incident disappeared during readback.")
    if (persisted != candidate) throw incidentCorrupt("Settled incident differs from its validated candidate.")
  }

  override fun expireDeadline(
    occurrenceToken: String,
    expectedRevision: Long,
    observedOwnerToken: String?,
    observedOwnerGeneration: Long?,
  ): Boolean {
    if (!validOccurrenceToken(occurrenceToken) || expectedRevision <= 0 ||
      (observedOwnerToken == null) != (observedOwnerGeneration == null)
    ) {
      throw incidentCorrupt("Block incident expiry input violates its persistence contract.")
    }
    val current = (lookup(occurrenceToken) as? BlockIncidentLookup.Found)?.incident ?: return false
    val now = clock.instant()
    val candidate =
      when (
        val decision =
          BlockIncidentTransitions.expireDeadline(
            current,
            now,
            expectedRevision,
            observedOwnerToken,
            observedOwnerGeneration,
          )
      ) {
        is BlockIncidentTransitionDecision.Accepted -> decision.candidate
        is BlockIncidentTransitionDecision.Refused -> return false
      }
    candidate.requireContract()
    val expired =
      connection.prepareStatement(
        "UPDATE block_incidents SET diagnosis = ?, revision = revision + 1, next_eligible_at = NULL, " +
          "retry_count = retry_count + ?, owner_token = NULL, owner_generation = NULL, host_identity = NULL, " +
          "process_id = NULL, lease_expires_at = NULL WHERE occurrence_token = ? AND revision = ? " +
          "AND diagnosis = ? AND owner_token IS ? AND owner_generation IS ?",
      ).use { statement ->
        statement.bindAll(
          BlockIncidentDiagnosis.TIMED_OUT.wireValue,
          if (current.state.claim == null) 0 else 1,
          occurrenceToken,
          expectedRevision,
          current.state.diagnosis.wireValue,
          observedOwnerToken,
          observedOwnerGeneration,
        )
        statement.executeUpdate() == 1
      }
    if (!expired) return false
    val persisted = load(occurrenceToken) ?: throw incidentCorrupt("Expired incident disappeared during readback.")
    if (persisted != candidate) throw incidentCorrupt("Expired incident differs from its validated candidate.")
    return true
  }

  private fun requireValidClaim(
    occurrenceToken: String,
    expectedRevision: Long,
    claim: BlockIncidentClaim,
  ) {
    val positiveCounters = expectedRevision > 0 && claim.ownerGeneration > 0 && claim.processId > 0
    val validIdentities = validIdentity(claim.ownerToken) && validIdentity(claim.hostIdentity)
    if (!positiveCounters || !validIdentities || !validOccurrenceToken(occurrenceToken)) {
      throw incidentCorrupt("Block incident claim input violates its persistence contract.")
    }
  }

  private fun validOccurrenceToken(value: String): Boolean = OCCURRENCE_TOKEN_PATTERN.matches(value)

  private fun validIdentity(value: String): Boolean = value.isNotBlank() && value.length <= MAX_IDENTITY_CHARS

  private fun BlockIncidentOccurrence.hasSamePersistedOriginalFailure(
    proposed: BlockIncidentOccurrence,
    compareObservedAt: Boolean,
  ): Boolean =
    persistedOriginalFailure() == proposed.persistedOriginalFailure() &&
      (!compareObservedAt || observedAt == proposed.observedAt)

  private fun BlockIncidentOccurrence.persistedOriginalFailure(): List<Any?> =
    listOf(
      occurrenceToken,
      workflowId,
      parentWorkflowId,
      phaseId,
      phaseRunId,
      invocationId,
      executionId,
      attempt,
      generation,
      origin,
      reason,
      failureCode,
      failureDisposition,
      throwableType,
      exitCode,
      evidenceAvailability,
      identityAvailability,
      exitBehavior,
    )

  override fun findByAlias(alias: BlockIncidentAlias): BlockIncident? {
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

  private fun persistAlias(
    alias: BlockIncidentAlias,
    occurrenceToken: String,
  ) {
    connection.prepareStatement(
      "INSERT INTO block_incident_aliases(alias_kind, alias_value, occurrence_token) VALUES (?, ?, ?)",
    ).use { statement ->
      statement.bindAll(alias.kind.wireValue, alias.value, occurrenceToken)
      statement.executeUpdate()
    }
  }

  private fun ResultSet.requireSupportedContractVersion() {
    if (requiredText("contract_version") != BLOCK_INCIDENT_CONTRACT_VERSION) {
      throw SkillBillRuntimeException(
        BlockIncidentFailureCode.INVALID_VERSION,
        "Stored block incident uses an unsupported contract version.",
      )
    }
  }

  private fun ResultSet.toIncident(): BlockIncident {
    requireSupportedContractVersion()
    val token = requiredText("occurrence_token")
    val columnOccurrence = columnOccurrence(token)
    val occurrence =
      getString("occurrence_json")?.let { raw ->
        SqliteBlockIncidentOccurrenceCodec.decode(raw).also { stored ->
          if (!stored.hasSamePersistedOriginalFailure(columnOccurrence, compareObservedAt = true)) {
            throw incidentCorrupt("Stored block incident occurrence differs from its indexed columns.")
          }
        }
      } ?: columnOccurrence
    val incident = BlockIncident(occurrence = occurrence, aliases = aliasesFor(token), state = storedState())
    if (!incident.isValid()) throw incidentCorrupt("Stored block incident violates its lifecycle contract.")
    incident.requireContract()
    return incident
  }

  private fun aliasesFor(token: String): List<BlockIncidentAlias> =
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

  private fun BlockIncident.requireContract() {
    contractValidator.validate(SqliteBlockIncidentOccurrenceCodec.encodeIncident(this))
  }

  companion object {
    private const val MAX_PENDING_BATCH = 64
    private const val MAX_PENDING_SCAN = 256
    private const val MAX_IDENTITY_CHARS = 512
    private const val MAX_QUARANTINE_MESSAGE_CHARS = 1024
    private const val MAX_ALIAS_CHARS = 512
    private val OCCURRENCE_TOKEN_PATTERN = Regex("bi_[0-9a-f]{64}")
  }
}
