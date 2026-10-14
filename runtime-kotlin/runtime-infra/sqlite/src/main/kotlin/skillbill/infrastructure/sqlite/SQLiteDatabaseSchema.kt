package skillbill.infrastructure.sqlite

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.ports.db.DatabaseSchemaPort
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics

@Inject
class SQLiteDatabaseSchema(
  private val database: DatabaseSessionFactory,
  private val diagnostics: RuntimeDiagnostics,
) : DatabaseSchemaPort {
  override fun migrateExistingDatabase(): Boolean {
    if (!database.databaseExists()) return false
    DatabaseRuntime.migrateExistingDatabase(database.resolveDbPath(), diagnostics)
    return true
  }
}
