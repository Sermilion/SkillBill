package skillbill.ports.db

interface DatabaseSchemaPort {
  /**
   * Migrates an existing database to this runtime's schema so read-only consumers, such as the desktop app,
   * do not have to wait for the first runtime write. Returns false when no database exists yet.
   */
  fun migrateExistingDatabase(): Boolean
}
