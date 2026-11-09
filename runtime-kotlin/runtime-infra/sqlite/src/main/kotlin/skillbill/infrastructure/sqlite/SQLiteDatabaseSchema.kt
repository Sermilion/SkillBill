package skillbill.infrastructure.sqlite

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.DatabaseAccessOperation
import skillbill.infrastructure.sqlite.core.schema.databaseAccessError
import skillbill.ports.db.DatabaseSchemaPort
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.DatabaseReadinessObservation
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.sql.SQLException

@Inject
class SQLiteDatabaseSchema(
  private val database: DatabaseSessionFactory,
  private val diagnostics: RuntimeDiagnostics,
) : DatabaseSchemaPort {
  override fun ensureDatabaseReady() {
    if (!migrateExistingDatabase()) {
      DatabaseRuntime.ensureWriteReady(database.resolveDbPath(), diagnostics)
    }
  }

  override fun migrateExistingDatabase(): Boolean {
    if (!database.databaseExists()) return false
    DatabaseRuntime.migrateExistingDatabase(database.resolveDbPath(), diagnostics)
    return true
  }

  override fun hasPendingMigrations(): Boolean =
    database.databaseExists() && DatabaseRuntime.hasPendingMigrations(database.resolveDbPath(), diagnostics)

  override fun observeReadiness(): DatabaseReadinessObservation {
    if (!database.databaseExists()) return DatabaseReadinessObservation(false, null, null, false)
    val path = database.resolveDbPath()
    val identity = requireNotNull(DatabaseIdentity.read(path))
    val ledgerVersion =
      try {
        DatabaseRuntime.openReadConnectionAt(path).use { opened ->
          opened.connection.createStatement().use { statement ->
            statement.executeQuery("SELECT MAX(version) FROM schema_migrations").use { rows ->
              if (!rows.next()) throw SQLException("Schema migration ledger has no version row.")
              rows.getInt(1)
            }
          }
        }
      } catch (error: SQLException) {
        throw databaseAccessError(path, DatabaseAccessOperation.READ, error)
      }
    return DatabaseReadinessObservation(
      existing = true,
      storeIdentity = "${identity.absolutePath}:${identity.fileIdentity}:${identity.fileSizeBytes}:$ledgerVersion",
      selectedSchemaVersion = ledgerVersion,
      requiresPreparation = DatabaseRuntime.hasPendingMigrations(path, diagnostics),
    )
  }
}
