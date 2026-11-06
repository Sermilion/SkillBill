package skillbill.infrastructure.sqlite

import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.error.core.DatabaseAccessOperation
import skillbill.error.core.databaseIncompatible
import skillbill.infrastructure.sqlite.core.migration.DatabaseMigration
import skillbill.infrastructure.sqlite.core.migration.MigrationLedger
import skillbill.infrastructure.sqlite.core.ops.DatabaseTransactionBeginMode
import skillbill.infrastructure.sqlite.core.ops.DatabaseTransactionSpec
import skillbill.infrastructure.sqlite.core.ops.inDatabaseTransaction
import skillbill.infrastructure.sqlite.core.schema.databasePath
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.sql.Connection
import java.util.UUID

internal object DatabaseMigrations {
  val migrations: List<DatabaseMigration> =
    databaseMigrations.also(::requireDeterministicMigrations)

  fun apply(
    connection: Connection,
    diagnostics: RuntimeDiagnostics,
    migrationSet: List<DatabaseMigration> = migrations,
  ) {
    val currentVersion = requireSupportedVersion(connection, migrationSet)
    val ledger = MigrationLedger.readState(connection)
    val latestVersion = migrationSet.maxOfOrNull { it.version } ?: 0
    if (!ledger.hasPendingWork(migrationSet) && currentVersion == latestVersion) return
    val dbPath = connection.databasePath()

    connection.inDatabaseTransaction(
      DatabaseTransactionSpec(
        dbPath = dbPath,
        beginMode = DatabaseTransactionBeginMode.IMMEDIATE,
        operation = DatabaseAccessOperation.OPEN,
        diagnostics = diagnostics,
      ),
    ) {
      requireSupportedVersion(this, migrationSet)
      MigrationLedger.ensureNameKeyed(this)
      val appliedNames = MigrationLedger.appliedNames(this)
      val pending = migrationSet.filterNot { migration -> migration.name in appliedNames }
      pending.forEach { migration ->
        migration.apply(this, diagnostics)
        MigrationLedger.record(this, migration)
      }
      if (pending.isNotEmpty()) refreshDesktopSignalGeneration(this)
      MigrationLedger.alignVersions(this, migrationSet)
      val appliedAfter = MigrationLedger.appliedNames(this)
      val highestVersion =
        migrationSet.filter { migration -> migration.name in appliedAfter }.maxOfOrNull { migration ->
          migration.version
        }
      if (highestVersion != null) {
        createStatement().use { statement -> statement.execute("PRAGMA user_version = $highestVersion") }
      }
    }
    diagnostics.info(
      "seam=database_schema_readiness record_kind=migration value_expected=$latestVersion " +
        "value_used=$latestVersion cause=pending_or_renumbered_migrations previous_version=$currentVersion",
    )
  }

  fun requireSupportedVersion(
    connection: Connection,
    migrationSet: List<DatabaseMigration> = migrations,
  ): Int {
    val latestVersion = migrationSet.maxOfOrNull { it.version } ?: 0
    val currentVersion =
      connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA user_version").use { rows ->
          check(rows.next()) { "PRAGMA user_version returned no row" }
          rows.getInt(1)
        }
      }
    val ledger = MigrationLedger.readState(connection)
    if (currentVersion > latestVersion || ledger.appliedVersions.values.any { it > latestVersion }) {
      throw databaseIncompatible(connection.databasePath().toString(), DatabaseAccessOperation.OPEN)
    }
    return currentVersion
  }

  private fun refreshDesktopSignalGeneration(connection: Connection) {
    val exists =
      connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
        statement.setString(1, DesktopReadSchema.GENERATION_TABLE)
        statement.executeQuery().use { rows -> rows.next() }
      }
    if (!exists) return
    connection.prepareStatement(
      "INSERT OR REPLACE INTO ${DesktopReadSchema.GENERATION_TABLE} (id, generation) VALUES (1, ?)",
    ).use { statement ->
      statement.setString(1, UUID.randomUUID().toString())
      statement.executeUpdate()
    }
  }

  private fun requireDeterministicMigrations(migrations: List<DatabaseMigration>) {
    val versions = migrations.map { migration -> migration.version }
    val names = migrations.map { migration -> migration.name }

    require(versions == versions.sorted()) { "Database migrations must be ordered by version." }
    require(versions.toSet().size == versions.size) { "Database migration versions must be unique." }
    require(names.toSet().size == names.size) { "Database migration names must be unique." }
    val expectedVersions =
      (1..DesktopReadSchema.VERSION).filter { version -> version != RETIRED_AUDIT_GENERATION_MIGRATION }
    require(versions == expectedVersions) {
      "Desktop schema ${DesktopReadSchema.VERSION} requires a migration for every version increase."
    }
  }

  private const val RETIRED_AUDIT_GENERATION_MIGRATION = 23
}
