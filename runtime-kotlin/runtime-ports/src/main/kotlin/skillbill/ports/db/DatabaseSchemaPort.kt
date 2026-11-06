package skillbill.ports.db

interface DatabaseSchemaPort {
  /**
   * Creates or migrates the runtime database so read-only consumers, such as the desktop app,
   * can connect immediately after installation. Refuses newer or unrecognized schemas without changing them.
   */
  fun ensureDatabaseReady()

  /**
   * Migrates an existing database and refuses newer or unrecognized schemas without changing them.
   * Returns false when no database exists yet, leaving the selected path absent.
   */
  fun migrateExistingDatabase(): Boolean

  /**
   * Reports whether an existing database has migrations this runtime would apply, without changing it.
   * Returns false when no database exists yet and refuses newer or unrecognized schemas.
   */
  fun hasPendingMigrations(): Boolean
}
