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
}
