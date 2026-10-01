package skillbill.infrastructure.sqlite.core.migration

import skillbill.error.core.DatabaseAccessOperation
import skillbill.infrastructure.sqlite.core.ops.DatabaseTransactionBeginMode
import skillbill.infrastructure.sqlite.core.ops.DatabaseTransactionSpec
import skillbill.infrastructure.sqlite.core.ops.inDatabaseTransaction
import skillbill.infrastructure.sqlite.core.schema.databasePath
import skillbill.infrastructure.sqlite.databaseMigrations
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.sql.Connection

internal object DatabaseMigrations {
  val migrations: List<DatabaseMigration> =
    databaseMigrations.also(::requireDeterministicMigrations)

  fun apply(
    connection: Connection,
    diagnostics: RuntimeDiagnostics,
    migrationSet: List<DatabaseMigration> = migrations,
  ) {
    val ledger = MigrationLedger.readState(connection)
    if (!ledger.hasPendingWork(migrationSet.map { migration -> migration.name })) return
    val dbPath = connection.databasePath()

    connection.inDatabaseTransaction(
      DatabaseTransactionSpec(
        dbPath = dbPath,
        beginMode = DatabaseTransactionBeginMode.IMMEDIATE,
        operation = DatabaseAccessOperation.OPEN,
        diagnostics = diagnostics,
      ),
    ) {
      MigrationLedger.ensureNameKeyed(this)
      val appliedNames = MigrationLedger.appliedNames(this)
      migrationSet
        .filterNot { migration -> migration.name in appliedNames }
        .forEach { migration ->
          migration.apply(this, diagnostics)
          MigrationLedger.record(this, migration)
        }
      val appliedAfter = MigrationLedger.appliedNames(this)
      val highestVersion =
        migrationSet.filter { migration -> migration.name in appliedAfter }.maxOfOrNull { migration ->
          migration.version
        }
      if (highestVersion != null) {
        createStatement().use { statement -> statement.execute("PRAGMA user_version = $highestVersion") }
      }
    }
  }

  private fun requireDeterministicMigrations(migrations: List<DatabaseMigration>) {
    val versions = migrations.map { migration -> migration.version }
    val names = migrations.map { migration -> migration.name }

    require(versions == versions.sorted()) { "Database migrations must be ordered by version." }
    require(versions.toSet().size == versions.size) { "Database migration versions must be unique." }
    require(names.toSet().size == names.size) { "Database migration names must be unique." }
  }
}
