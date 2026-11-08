package skillbill.infrastructure.sqlite.core.migration.area

import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import java.sql.Connection

internal fun addOperatorApprovalRecordsAndRules(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS approval_requests (
        approval_id TEXT NOT NULL,
        repository_identity TEXT NOT NULL,
        run_id TEXT NOT NULL,
        process_generation TEXT NOT NULL,
        provider TEXT NOT NULL,
        provider_request_id_type TEXT NOT NULL CHECK (provider_request_id_type IN ('string', 'integer')),
        provider_request_id TEXT NOT NULL,
        record_json TEXT NOT NULL,
        terminal_decision_json TEXT,
        dispatch_claimed_at TEXT,
        delivery_state TEXT NOT NULL DEFAULT 'not_claimed'
          CHECK (delivery_state IN ('not_claimed', 'claimed', 'written', 'uncertain')),
        PRIMARY KEY (approval_id, repository_identity, run_id, process_generation, provider,
          provider_request_id_type, provider_request_id)
      )
      """.trimIndent(),
    )
    statement.execute(
      "CREATE INDEX IF NOT EXISTS idx_approval_requests_pending ON " +
        "approval_requests(repository_identity, run_id) " +
        "WHERE terminal_decision_json IS NULL",
    )
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS approval_rules (
        rule_id TEXT PRIMARY KEY,
        repository_identity TEXT NOT NULL,
        provider TEXT NOT NULL,
        profile_identity TEXT NOT NULL,
        protocol_revision TEXT NOT NULL,
        action_kind TEXT NOT NULL,
        working_directory TEXT NOT NULL,
        action_fingerprint TEXT NOT NULL,
        created_at TEXT NOT NULL,
        revoked_at TEXT
      )
      """.trimIndent(),
    )
    statement.execute(
      "CREATE INDEX IF NOT EXISTS idx_approval_rules_match ON " +
        "approval_rules(repository_identity, provider, " +
        "profile_identity, protocol_revision, action_kind, working_directory, " +
        "action_fingerprint) " +
        "WHERE revoked_at IS NULL",
    )
  }
}

internal fun addOperatorApprovalLeasesAndGrantFacts(connection: Connection) {
  DatabaseColumnMigrations.ensureColumn(
    connection,
    "approval_requests",
    "action_class",
    "TEXT NOT NULL DEFAULT 'provider_interaction'",
  )
  DatabaseColumnMigrations.ensureColumn(connection, "approval_requests", "write_capable", "INTEGER NOT NULL DEFAULT 1")
  DatabaseColumnMigrations.ensureColumn(connection, "approval_requests", "input_complete", "INTEGER NOT NULL DEFAULT 0")
  DatabaseColumnMigrations.ensureColumn(
    connection,
    "approval_requests",
    "provider_disposition",
    "TEXT NOT NULL DEFAULT 'none'",
  )
  connection.createStatement().use { statement ->
    statement.execute(
      "CREATE INDEX IF NOT EXISTS idx_approval_requests_owner_pending ON " +
        "approval_requests(" +
        "repository_identity, run_id, process_generation) WHERE " +
        "terminal_decision_json IS NULL",
    )
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS approval_owners (
        repository_identity TEXT NOT NULL,
        run_id TEXT NOT NULL,
        process_generation TEXT NOT NULL,
        nonce TEXT NOT NULL,
        execution_mode TEXT NOT NULL CHECK (execution_mode IN ('read_only', 'act')),
        enclosing_deadline TEXT,
        created_at TEXT NOT NULL,
        ended_at TEXT,
        PRIMARY KEY (repository_identity, run_id, process_generation)
      )
      """.trimIndent(),
    )
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS approval_consumers (
        repository_identity TEXT PRIMARY KEY,
        consumer_id TEXT NOT NULL,
        nonce TEXT NOT NULL,
        created_at TEXT NOT NULL,
        expires_at TEXT NOT NULL,
        ended_at TEXT
      )
      """.trimIndent(),
    )
  }
}

internal fun addOperatorApprovalOwnerLeaseExpiry(connection: Connection) {
  DatabaseColumnMigrations.ensureColumn(connection, "approval_owners", "expires_at", "TEXT")
  connection.createStatement().use { statement ->
    statement.execute(
      "CREATE INDEX IF NOT EXISTS idx_approval_requests_owner_claimed ON " +
        "approval_requests(" +
        "repository_identity, run_id, process_generation) WHERE delivery_state = " +
        "'claimed'",
    )
  }
}

internal fun addApprovalNotRequiredDelivery(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute(
      "ALTER TABLE approval_requests RENAME TO " +
        "approval_requests_before_not_required",
    )
    statement.execute(
      """
      CREATE TABLE approval_requests (
        approval_id TEXT NOT NULL,
        repository_identity TEXT NOT NULL,
        run_id TEXT NOT NULL,
        process_generation TEXT NOT NULL,
        provider TEXT NOT NULL,
        provider_request_id_type TEXT NOT NULL CHECK (provider_request_id_type IN ('string', 'integer')),
        provider_request_id TEXT NOT NULL,
        record_json TEXT NOT NULL,
        terminal_decision_json TEXT,
        dispatch_claimed_at TEXT,
        delivery_state TEXT NOT NULL DEFAULT 'not_claimed'
          CHECK (delivery_state IN ('not_claimed', 'claimed', 'written', 'uncertain', 'not_required')),
        action_class TEXT NOT NULL DEFAULT 'provider_interaction',
        write_capable INTEGER NOT NULL DEFAULT 1,
        input_complete INTEGER NOT NULL DEFAULT 0,
        provider_disposition TEXT NOT NULL DEFAULT 'none',
        PRIMARY KEY (approval_id, repository_identity, run_id, process_generation, provider,
          provider_request_id_type, provider_request_id)
      )
      """.trimIndent(),
    )
    statement.execute(
      """
      INSERT INTO approval_requests (
        approval_id, repository_identity, run_id, process_generation, provider, provider_request_id_type,
        provider_request_id, record_json, terminal_decision_json, dispatch_claimed_at, delivery_state,
        action_class, write_capable, input_complete, provider_disposition
      ) SELECT approval_id, repository_identity, run_id, process_generation, provider, provider_request_id_type,
        provider_request_id, record_json, terminal_decision_json, dispatch_claimed_at, delivery_state,
        action_class, write_capable, input_complete, provider_disposition FROM approval_requests_before_not_required
      """.trimIndent(),
    )
    statement.execute("DROP TABLE approval_requests_before_not_required")
    statement.execute(
      "CREATE INDEX IF NOT EXISTS idx_approval_requests_pending ON " +
        "approval_requests(repository_identity, run_id) " +
        "WHERE terminal_decision_json IS NULL",
    )
    statement.execute(
      "CREATE INDEX IF NOT EXISTS idx_approval_requests_owner_pending ON " +
        "approval_requests(" +
        "repository_identity, run_id, process_generation) WHERE " +
        "terminal_decision_json IS NULL",
    )
    statement.execute(
      "CREATE INDEX IF NOT EXISTS idx_approval_requests_owner_claimed ON " +
        "approval_requests(" +
        "repository_identity, run_id, process_generation) WHERE delivery_state = " +
        "'claimed'",
    )
  }
}
