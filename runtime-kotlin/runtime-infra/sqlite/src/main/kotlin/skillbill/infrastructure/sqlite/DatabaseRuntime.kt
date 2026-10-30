package skillbill.infrastructure.sqlite

import org.sqlite.SQLiteConfig
import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.error.core.DatabaseAccessOperation
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.databaseAccessFailure
import skillbill.error.core.databaseIncompatible
import skillbill.infrastructure.sqlite.core.migration.MigrationLedger
import skillbill.infrastructure.sqlite.core.schema.DatabasePaths
import skillbill.infrastructure.sqlite.core.schema.DatabaseSchema
import skillbill.infrastructure.sqlite.core.schema.databaseAccessError
import skillbill.infrastructure.sqlite.core.schema.databaseFileIdentity
import skillbill.infrastructure.sqlite.core.schema.fileObservation
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.filesystem.model.DatabaseFileObservation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

internal data class OpenDatabase(
  internal val connection: Connection,
  internal val dbPath: Path,
  internal val fileObservation: DatabaseFileObservation,
) : AutoCloseable {
  override fun close() {
    connection.close()
  }
}

internal object DatabaseRuntime {
  const val BUSY_TIMEOUT_MILLIS: Int = 5000
  const val SELF_MANAGED_WRITE_BUSY_ATTEMPTS: Int = 3

  private val writeReadinessGate = DatabaseWriteReadinessGate()

  fun ensureWriteReady(
    path: Path,
    diagnostics: RuntimeDiagnostics,
  ) {
    val normalized = path.toAbsolutePath().normalize()
    writeReadinessGate.ensureReady(normalized) {
      establishSchemaReadiness(normalized, diagnostics)
    }
  }

  fun resolveDbPath(
    cliValue: String?,
    environment: Map<String, String>,
    userHome: Path,
  ): Path = DatabasePaths.resolveDbPath(cliValue = cliValue, environment = environment, userHome = userHome)

  fun openDb(
    cliValue: String?,
    environment: Map<String, String>,
    userHome: Path,
    diagnostics: RuntimeDiagnostics,
  ): OpenDatabase {
    val dbPath = resolveDbPath(cliValue = cliValue, environment = environment, userHome = userHome)
    return openDbAt(dbPath, diagnostics)
  }

  fun openDbAt(
    dbPath: Path,
    diagnostics: RuntimeDiagnostics,
  ): OpenDatabase {
    ensureWriteReady(dbPath, diagnostics)
    return openWriteDbAt(dbPath, diagnostics)
  }

  fun openWriteDbAt(
    dbPath: Path,
    diagnostics: RuntimeDiagnostics? = null,
  ): OpenDatabase {
    val before = fileObservation(dbPath)
    val connection = openWriteConnectionAt(dbPath, diagnostics)
    return connection.closingOnFailure(diagnostics) { openedDatabase(connection, dbPath, before) }
  }

  fun establishSchemaReadiness(
    path: Path,
    diagnostics: RuntimeDiagnostics,
  ) {
    path.parent?.toAbsolutePath()?.normalize()?.toFile()?.mkdirs()
    asTypedFailure(path, DatabaseAccessOperation.OPEN) {
      DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath().normalize()}").use { connection ->
        connection.closingOnFailure(diagnostics) {
          asTypedFailure(path, DatabaseAccessOperation.OPEN) {
            configureConnection(connection, enableWal = true)
            DatabaseSchema.createBaseSchema(connection)
            DatabaseMigrations.apply(connection, diagnostics)
          }
        }
      }
    }
  }

  fun openWriteConnectionAt(
    path: Path,
    diagnostics: RuntimeDiagnostics? = null,
  ): Connection {
    val connection =
      asTypedFailure(path, DatabaseAccessOperation.OPEN) {
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath().normalize()}")
      }
    return connection.closingOnFailure(diagnostics) {
      asTypedFailure(path, DatabaseAccessOperation.OPEN) {
        configureConnection(connection, enableWal = true)
      }
      connection
    }
  }

  fun openReadDb(
    cliValue: String?,
    environment: Map<String, String>,
    userHome: Path,
    diagnostics: RuntimeDiagnostics,
  ): OpenDatabase {
    val dbPath = resolveDbPath(cliValue = cliValue, environment = environment, userHome = userHome)
    return openReadDbAt(dbPath, diagnostics)
  }

  fun openReadDbAt(
    dbPath: Path,
    diagnostics: RuntimeDiagnostics,
  ): OpenDatabase {
    if (!Files.exists(dbPath) || isSchemaless(dbPath)) return openDbAt(dbPath, diagnostics)
    return openReadOnlyDb(dbPath, diagnostics)
  }

  fun openExistingReadDbAt(
    dbPath: Path,
    diagnostics: RuntimeDiagnostics,
  ): OpenDatabase =
    try {
      openCompatibleReadOnlyDb(dbPath, diagnostics)
    } catch (error: SkillBillRuntimeException) {
      diagnostics.warning("Database read refused with ${error.code}.")
      throw error
    }

  internal fun openReadConnectionAt(dbPath: Path): OpenDatabase = openReadOnlyDb(dbPath)

  private fun openReadOnlyDb(
    dbPath: Path,
    diagnostics: RuntimeDiagnostics? = null,
  ): OpenDatabase {
    val before = fileObservation(dbPath, DatabaseAccessOperation.READ)
    val connection =
      asTypedFailure(dbPath, DatabaseAccessOperation.READ) {
        DriverManager.getConnection(
          "jdbc:sqlite:${dbPath.toAbsolutePath().normalize()}",
          SQLiteConfig().apply { setReadOnly(true) }.toProperties(),
        )
      }
    return connection.closingOnFailure(diagnostics) {
      asTypedFailure(dbPath, DatabaseAccessOperation.READ) {
        configureConnection(connection, enableWal = false)
      }
      openedDatabase(connection, dbPath, before)
    }
  }

  internal fun openCompatibleReadOnlyDb(
    dbPath: Path,
    diagnostics: RuntimeDiagnostics,
  ): OpenDatabase {
    val database = openReadOnlyDb(dbPath, diagnostics)
    return database.connection.closingOnFailure(diagnostics) {
      asTypedFailure(dbPath, DatabaseAccessOperation.READ) {
        val state = MigrationLedger.readState(database.connection)
        val latestVersion = DatabaseMigrations.migrations.maxOfOrNull { it.version } ?: 0
        val currentVersion = schemaVersion(database.connection)
        val requiredMigrations = DatabaseMigrations.migrations.map { it.name }.toSet()
        val completeLedger =
          state.tableExists && !state.versionKeyed &&
            state.appliedNames.containsAll(
              requiredMigrations,
            )
        if (!completeLedger || currentVersion != latestVersion || !hasDesktopReadIdentity(database.connection)) {
          throw databaseIncompatible(dbPath.toAbsolutePath().normalize().toString(), DatabaseAccessOperation.READ)
        }
      }
      database
    }
  }

  fun ensureDatabase(
    path: Path,
    diagnostics: RuntimeDiagnostics,
  ): Connection {
    establishSchemaReadiness(path, diagnostics)
    return openWriteConnectionAt(path, diagnostics)
  }

  fun migrateExistingDatabase(
    path: Path,
    diagnostics: RuntimeDiagnostics,
  ) {
    val pending =
      openReadOnlyDb(path, diagnostics).use { database ->
        asTypedFailure(path, DatabaseAccessOperation.OPEN) {
          val connection = database.connection
          val ledger = MigrationLedger.readState(connection)
          val migrations = DatabaseMigrations.migrations
          val migrationNames = migrations.map { it.name }
          val latestVersion = migrations.maxOf { it.version }
          val version = schemaVersion(connection)
          if (!ledger.tableExists || ledger.appliedNames.intersect(migrationNames.toSet()).isEmpty() ||
            version > latestVersion
          ) {
            throw databaseIncompatible(path.toString(), DatabaseAccessOperation.OPEN)
          }
          val ledgerVersion =
            connection.createStatement().use { statement ->
              statement.executeQuery("SELECT MAX(version) FROM schema_migrations").use { rows ->
                rows.next()
                rows.getInt(1)
              }
            }
          val desktopIdentityInstalled =
            connection.createStatement().use { statement ->
              statement.executeQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'room_master_table'",
              ).use { it.next() }
            }
          if (ledgerVersion > latestVersion || desktopIdentityInstalled && !hasDesktopReadIdentity(connection)) {
            throw databaseIncompatible(path.toString(), DatabaseAccessOperation.OPEN)
          }
          ledger.hasPendingWork(migrations)
        }
      }
    if (pending) establishSchemaReadiness(path, diagnostics)
  }

  private fun isSchemaless(dbPath: Path): Boolean =
    asTypedFailure(dbPath, DatabaseAccessOperation.READ) {
      DriverManager.getConnection(
        "jdbc:sqlite:${dbPath.toAbsolutePath().normalize()}",
        SQLiteConfig().apply { setReadOnly(true) }.toProperties(),
      ).use { connection ->
        connection.createStatement().use { statement ->
          statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table'").use { resultSet ->
            resultSet.next() && resultSet.getInt(1) == 0
          }
        }
      }
    }

  private fun configureConnection(
    connection: Connection,
    enableWal: Boolean,
  ) {
    connection.createStatement().use { statement ->

      statement.execute("PRAGMA busy_timeout = $BUSY_TIMEOUT_MILLIS")
      if (enableWal) statement.execute("PRAGMA journal_mode = WAL")
      statement.execute("PRAGMA foreign_keys = ON")
      if (!enableWal) statement.execute("PRAGMA query_only = ON")
    }
  }
}

private fun openedDatabase(
  connection: Connection,
  path: Path,
  before: DatabaseFileObservation,
): OpenDatabase {
  val after = fileObservation(path)
  if (before != after) {
    val failure =
      databaseAccessFailure(
        path.toAbsolutePath().normalize().toString(),
        DatabaseAccessOperation.OPEN,
        "file identity changed while the connection opened",
      )
    runCatching { connection.close() }.onFailure(failure::addSuppressed)
    throw failure
  }
  return OpenDatabase(connection, path, after)
}

private fun <T> asTypedFailure(
  dbPath: Path,
  operation: DatabaseAccessOperation,
  block: () -> T,
): T =
  try {
    block()
  } catch (error: SQLException) {
    throw databaseAccessError(dbPath, operation, error)
  }

private fun <T> Connection.closingOnFailure(
  diagnostics: RuntimeDiagnostics?,
  block: () -> T,
): T {
  return runCatching(block).getOrElse { failure ->
    runCatching { close() }.onFailure { closeFailure ->
      failure.addSuppressed(closeFailure)
      diagnostics?.warning("SQLite connection close failed after an earlier failure.")
    }
    throw failure
  }
}

private fun schemaVersion(connection: Connection): Int =
  connection.createStatement().use { statement ->
    statement.executeQuery("PRAGMA user_version").use { rows ->
      if (!rows.next()) throw SQLException("PRAGMA user_version returned no row")
      rows.getInt(1)
    }
  }

private fun hasDesktopReadIdentity(connection: Connection): Boolean {
  val exists =
    connection.createStatement().use { statement ->
      statement.executeQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'room_master_table'").use {
        it.next()
      }
    }
  return exists &&
    connection.prepareStatement("SELECT identity_hash FROM room_master_table WHERE id = 42").use { statement ->
      statement.executeQuery().use { rows -> rows.next() && rows.getString(1) == DesktopReadSchema.ROOM_IDENTITY_HASH }
    }
}

internal data class DatabaseIdentity(
  internal val absolutePath: String,
  internal val userVersion: Int,
  internal val fileIdentity: String,
  internal val fileSizeBytes: Long,
) {
  fun matches(current: DatabaseIdentity): Boolean =
    current.absolutePath == absolutePath &&
      current.userVersion == userVersion &&
      current.fileIdentity == fileIdentity &&
      current.fileSizeBytes >= fileSizeBytes

  companion object {
    fun read(path: Path): DatabaseIdentity? {
      if (!Files.exists(path)) return null
      val normalized = path.toAbsolutePath().normalize()
      val attrs = Files.readAttributes(normalized, BasicFileAttributes::class.java)
      val userVersion = readUserVersion(normalized)
      return DatabaseIdentity(
        absolutePath = normalized.toString(),
        userVersion = userVersion,
        fileIdentity = databaseFileIdentity(attrs),
        fileSizeBytes = attrs.size(),
      )
    }

    internal fun readUserVersion(path: Path): Int {
      try {
        DatabaseRuntime.openReadConnectionAt(path).use { database ->
          val connection = database.connection
          connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { rows ->
              if (!rows.next()) {
                throw databaseAccessError(
                  path,
                  DatabaseAccessOperation.READ,
                  SQLException("PRAGMA user_version returned no row"),
                )
              }
              return rows.getInt(1)
            }
          }
        }
      } catch (error: SQLException) {
        throw databaseAccessError(path, DatabaseAccessOperation.READ, error)
      }
    }
  }
}

internal class DatabaseWriteReadinessGate {
  @Volatile
  private var published: DatabaseIdentity? = null
  private val lock = Any()

  fun ensureReady(
    dbPath: Path,
    establishSchema: () -> Unit,
  ) {
    val normalized = dbPath.toAbsolutePath().normalize()
    val cached = published
    val current = DatabaseIdentity.read(normalized)
    if (cached != null && current != null && cached.matches(current)) {
      return
    }
    synchronized(lock) {
      val cachedAgain = published
      val currentAgain = DatabaseIdentity.read(normalized)
      if (cachedAgain != null && currentAgain != null && cachedAgain.matches(currentAgain)) {
        return
      }
      published = null
      runCatching {
        establishSchema()
        published = DatabaseIdentity.read(normalized)
          ?: error("Database readiness completed but identity could not be read at '$normalized'.")
      }.onFailure {
        published = null
      }.getOrThrow()
    }
  }
}
