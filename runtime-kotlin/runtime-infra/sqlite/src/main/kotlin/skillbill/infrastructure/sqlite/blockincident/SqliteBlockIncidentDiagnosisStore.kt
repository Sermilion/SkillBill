package skillbill.infrastructure.sqlite.blockincident

import skillbill.contracts.workflow.identity.blockincident.BlockIncidentDiagnosisKeys
import skillbill.error.blockincident.BlockIncidentFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCertainty
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDegradation
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisEvidence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisEvidenceKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisRecord
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentInvestigatorKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentPrerequisite
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentPrerequisiteCheck
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentPrerequisiteKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAction
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentResponsibleCategory
import skillbill.workflow.taskruntime.model.blockincident.validation
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.util.HexFormat

internal class SqliteBlockIncidentDiagnosisStore(
  private val connection: Connection,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics?,
) {
  fun insert(record: BlockIncidentDiagnosisRecord) {
    connection.prepareStatement(
      "INSERT INTO block_incident_diagnoses(occurrence_token, revision, summary, what_happened, cause, certainty, " +
        "uncertainty_note, responsible_category, proposed_recovery_action, recovery_effects, no_workaround_known, " +
        "degradations, investigator_kind, completed_at, reproduction_steps, provider_id, " +
        "phase_strategy_id, model_id) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    ).use { statement ->
      statement.bindAll(
        record.occurrenceToken,
        record.revision,
        record.summary,
        record.whatHappened,
        record.cause,
        record.certainty.wireValue,
        record.uncertaintyNote,
        record.responsibleCategory.wireValue,
        record.proposedRecoveryAction.wireValue,
        record.recoveryEffects,
        record.noWorkaroundKnown,
        record.degradations.map { it.wireValue }.sorted()
          .joinToString(BlockIncidentDiagnosisKeys.DEGRADATION_SEPARATOR),
        record.investigatorKind.wireValue,
        record.completedAt.toString(),
        record.reproductionSteps,
        record.providerId,
        record.phaseStrategyId,
        record.modelId,
      )
      statement.executeUpdate()
    }
    record.evidence.forEachIndexed { ordinal, item ->
      connection.prepareStatement(
        "INSERT INTO block_incident_diagnosis_evidence(occurrence_token, revision, ordinal, kind, reference, " +
          "summary, sha256, trusted) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
      ).use { statement ->
        statement.bindAll(
          record.occurrenceToken,
          record.revision,
          ordinal,
          item.kind.wireValue,
          item.reference,
          item.summary,
          item.sha256,
          item.trusted,
        )
        statement.executeUpdate()
      }
    }
    record.prerequisites.forEachIndexed { ordinal, item ->
      connection.prepareStatement(
        "INSERT INTO block_incident_diagnosis_prerequisites(occurrence_token, revision, ordinal, kind, check_kind) " +
          "VALUES (?, ?, ?, ?, ?)",
      ).use { statement ->
        statement.bindAll(record.occurrenceToken, record.revision, ordinal, item.kind.wireValue, item.check.wireValue)
        statement.executeUpdate()
      }
    }
  }

  fun read(occurrenceToken: String): List<BlockIncidentDiagnosisRecord> {
    val rows =
      connection.prepareStatement(
        "SELECT * FROM block_incident_diagnoses d WHERE d.occurrence_token = ? AND NOT EXISTS (" +
          "SELECT 1 FROM block_incident_diagnosis_quarantine q WHERE q.occurrence_token = d.occurrence_token " +
          "AND q.revision = d.revision) ORDER BY d.revision",
      ).use { statement ->
        statement.setString(1, occurrenceToken)
        statement.executeQuery().use { result ->
          generateSequence { if (result.next()) result.rawDiagnosisRow() else null }.toList()
        }
      }
    return rows.mapNotNull { row ->
      try {
        decode(row).also { record ->
          if (record.validation().isNotEmpty()) throw incidentCorrupt("Block incident diagnosis failed validation.")
        }
      } catch (failure: SkillBillRuntimeException) {
        if (failure.code != BlockIncidentFailureCode.INVALID_SCHEMA) throw failure
        quarantine(row, failure)
        null
      }
    }
  }

  private fun decode(row: RawDiagnosisRow): BlockIncidentDiagnosisRecord {
    val values = row.values

    fun text(column: String): String = values[column] ?: throw incidentCorrupt("Diagnosis $column is absent.")
    return BlockIncidentDiagnosisRecord(
      occurrenceToken = row.occurrenceToken,
      revision = row.revision,
      summary = text("summary"),
      whatHappened = text("what_happened"),
      cause = values["cause"],
      certainty = text("certainty").decodeEnum(BlockIncidentCertainty.entries) { it.wireValue },
      uncertaintyNote = values["uncertainty_note"],
      responsibleCategory =
        text("responsible_category").decodeEnum(BlockIncidentResponsibleCategory.entries) { it.wireValue },
      evidence = evidence(row),
      prerequisites = prerequisites(row),
      proposedRecoveryAction =
        text("proposed_recovery_action").decodeEnum(BlockIncidentRecoveryAction.entries) { it.wireValue },
      recoveryEffects = values["recovery_effects"],
      noWorkaroundKnown = text("no_workaround_known").decodeFlag(),
      degradations =
        text("degradations").split(BlockIncidentDiagnosisKeys.DEGRADATION_SEPARATOR).filter(String::isNotEmpty)
          .map { it.decodeEnum(BlockIncidentDegradation.entries) { degradation -> degradation.wireValue } }
          .toSet(),
      investigatorKind = text("investigator_kind").decodeEnum(BlockIncidentInvestigatorKind.entries) { it.wireValue },
      completedAt = text("completed_at").toInstant("completed_at"),
      reproductionSteps = values["reproduction_steps"],
      providerId = values["provider_id"],
      phaseStrategyId = values["phase_strategy_id"],
      modelId = values["model_id"],
    )
  }

  private fun evidence(row: RawDiagnosisRow): List<BlockIncidentDiagnosisEvidence> =
    childRows("block_incident_diagnosis_evidence", row) { result ->
      BlockIncidentDiagnosisEvidence(
        kind = result.requiredText("kind").decodeEnum(BlockIncidentDiagnosisEvidenceKind.entries) { it.wireValue },
        reference = result.requiredText("reference"),
        summary = result.requiredText("summary"),
        sha256 = result.requiredText("sha256"),
        trusted = result.requiredText("trusted").decodeFlag(),
      )
    }

  private fun prerequisites(row: RawDiagnosisRow): List<BlockIncidentPrerequisite> =
    childRows("block_incident_diagnosis_prerequisites", row) { result ->
      BlockIncidentPrerequisite(
        kind = result.requiredText("kind").decodeEnum(BlockIncidentPrerequisiteKind.entries) { it.wireValue },
        check = result.requiredText("check_kind").decodeEnum(BlockIncidentPrerequisiteCheck.entries) { it.wireValue },
      )
    }

  private fun <T> childRows(
    table: String,
    row: RawDiagnosisRow,
    decode: (ResultSet) -> T,
  ): List<T> =
    connection.prepareStatement("SELECT * FROM $table WHERE occurrence_token = ? AND revision = ? ORDER BY ordinal")
      .use { statement ->
        statement.bindAll(row.occurrenceToken, row.revision)
        statement.executeQuery().use { result ->
          generateSequence { if (result.next()) decode(result) else null }.toList()
        }
      }

  private fun quarantine(
    row: RawDiagnosisRow,
    failure: SkillBillRuntimeException,
  ) {
    val label = "Block incident ${row.occurrenceToken} diagnosis revision ${row.revision}"
    val durable =
      !connection.isReadOnly &&
        try {
          connection.prepareStatement(
            "INSERT OR IGNORE INTO block_incident_diagnosis_quarantine(occurrence_token, revision, failure_code, " +
              "failure_message, raw_row_digest, quarantined_at) VALUES (?, ?, ?, ?, ?, ?)",
          ).use { statement ->
            statement.bindAll(
              row.occurrenceToken,
              row.revision,
              BlockIncidentFailureCode.QUARANTINE.name,
              (failure.message ?: "Invalid block incident diagnosis.").take(MAX_QUARANTINE_MESSAGE_CHARS),
              row.digest(),
              clock.instant().toString(),
            )
            statement.executeUpdate()
          }
          true
        } catch (writeFailure: SQLException) {
          diagnostics?.warning("$label could not be quarantined.", writeFailure)
          false
        }
    val outcome = if (durable) " and is quarantined." else "; quarantine is pending a writable session."
    diagnostics?.warning("$label failed validation$outcome")
  }

  private class RawDiagnosisRow(val occurrenceToken: String, val revision: Long, val values: Map<String, String?>) {
    fun digest(): String {
      val raw = values.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" }
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest("$occurrenceToken:$revision:$raw".toByteArray()),
      )
    }
  }

  private fun ResultSet.rawDiagnosisRow(): RawDiagnosisRow =
    RawDiagnosisRow(
      occurrenceToken = getString("occurrence_token"),
      revision = getLong("revision"),
      values = DIAGNOSIS_COLUMNS.associateWith { getString(it) },
    )

  private fun String.decodeFlag(): Boolean =
    when (this) {
      "1" -> true
      "0" -> false
      else -> throw incidentCorrupt("Invalid block incident diagnosis flag.")
    }

  private companion object {
    const val MAX_QUARANTINE_MESSAGE_CHARS = 1024
    val DIAGNOSIS_COLUMNS =
      listOf(
        "summary",
        "what_happened",
        "cause",
        "certainty",
        "uncertainty_note",
        "responsible_category",
        "proposed_recovery_action",
        "recovery_effects",
        "no_workaround_known",
        "degradations",
        "investigator_kind",
        "completed_at",
        "reproduction_steps",
        "provider_id",
        "phase_strategy_id",
        "model_id",
      )
  }
}
