package skillbill.infrastructure.sqlite.core.migration.area.blockincident

import skillbill.infrastructure.sqlite.core.schema.addBlockIncidentDiagnosisRevisionTriggers
import java.sql.Connection

internal object BlockIncidentDiagnosisMigration {
  fun apply(connection: Connection) {
    connection.createStatement().use { statement ->
      (TABLES + IMMUTABILITY_TRIGGERS).forEach(statement::execute)
    }
    addBlockIncidentDiagnosisRevisionTriggers(connection)
  }

  private val TABLES =
    listOf(
      """
      CREATE TABLE IF NOT EXISTS block_incident_diagnoses (
        occurrence_token TEXT NOT NULL REFERENCES block_incidents(occurrence_token),
        revision INTEGER NOT NULL,
        summary TEXT NOT NULL,
        what_happened TEXT NOT NULL,
        cause TEXT,
        certainty TEXT NOT NULL,
        uncertainty_note TEXT,
        responsible_category TEXT NOT NULL,
        proposed_recovery_action TEXT NOT NULL,
        recovery_effects TEXT,
        no_workaround_known INTEGER NOT NULL CHECK (no_workaround_known IN (0, 1)),
        degradations TEXT NOT NULL,
        investigator_kind TEXT NOT NULL,
        completed_at TEXT NOT NULL,
        PRIMARY KEY (occurrence_token, revision)
      )
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS block_incident_diagnosis_evidence (
        occurrence_token TEXT NOT NULL,
        revision INTEGER NOT NULL,
        ordinal INTEGER NOT NULL,
        kind TEXT NOT NULL,
        reference TEXT NOT NULL,
        summary TEXT NOT NULL,
        sha256 TEXT NOT NULL,
        trusted INTEGER NOT NULL CHECK (trusted IN (0, 1)),
        PRIMARY KEY (occurrence_token, revision, ordinal),
        FOREIGN KEY (occurrence_token, revision) REFERENCES block_incident_diagnoses(occurrence_token, revision)
      )
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS block_incident_diagnosis_prerequisites (
        occurrence_token TEXT NOT NULL,
        revision INTEGER NOT NULL,
        ordinal INTEGER NOT NULL,
        kind TEXT NOT NULL,
        check_kind TEXT NOT NULL,
        PRIMARY KEY (occurrence_token, revision, ordinal),
        FOREIGN KEY (occurrence_token, revision) REFERENCES block_incident_diagnoses(occurrence_token, revision)
      )
      """.trimIndent(),
      """
      CREATE TABLE IF NOT EXISTS block_incident_diagnosis_quarantine (
        occurrence_token TEXT NOT NULL,
        revision INTEGER NOT NULL,
        failure_code TEXT NOT NULL,
        failure_message TEXT NOT NULL,
        raw_row_digest TEXT NOT NULL,
        quarantined_at TEXT NOT NULL,
        PRIMARY KEY (occurrence_token, revision)
      )
      """.trimIndent(),
    )

  private val IMMUTABLE_TABLES =
    listOf(
      "block_incident_diagnoses",
      "block_incident_diagnosis_evidence",
      "block_incident_diagnosis_prerequisites",
    )

  private val IMMUTABILITY_TRIGGERS =
    IMMUTABLE_TABLES.flatMap { table ->
      listOf(
        """
        CREATE TRIGGER IF NOT EXISTS ${table}_immutable_update
        BEFORE UPDATE ON $table
        BEGIN
          SELECT RAISE(ABORT, 'block incident diagnosis history is immutable');
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS ${table}_immutable_delete
        BEFORE DELETE ON $table
        BEGIN
          SELECT RAISE(ABORT, 'block incident diagnosis history is retained');
        END
        """.trimIndent(),
      )
    }
}
