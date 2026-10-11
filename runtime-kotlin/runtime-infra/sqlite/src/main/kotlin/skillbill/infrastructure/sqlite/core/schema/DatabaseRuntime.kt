package skillbill.infrastructure.sqlite.core.schema

import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.error.core.DatabaseAccessOperation
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.databaseAccessFailure
import skillbill.error.core.databaseBusy
import skillbill.error.core.databaseCorrupt
import skillbill.error.core.databaseIncompatible
import skillbill.error.core.databaseMissing
import skillbill.infrastructure.sqlite.core.migration.DatabaseMigrations
import skillbill.infrastructure.sqlite.core.migration.MigrationLedger
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.filesystem.model.DatabaseFileObservation
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
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

  private fun openCompatibleReadOnlyDb(
    dbPath: Path,
    diagnostics: RuntimeDiagnostics,
  ): OpenDatabase {
    val database = openReadOnlyDb(dbPath, diagnostics)
    return database.connection.closingOnFailure(diagnostics) {
      asTypedFailure(dbPath, DatabaseAccessOperation.READ) {
        val state = MigrationLedger.readState(database.connection)
        val latestVersion = DatabaseMigrations.migrations.maxOfOrNull { it.version } ?: 0
        val currentVersion =
          database.connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { rows ->
              if (!rows.next()) throw SQLException("PRAGMA user_version returned no row")
              rows.getInt(1)
            }
          }
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

internal fun fileObservation(
  path: Path,
  operation: DatabaseAccessOperation = DatabaseAccessOperation.OPEN,
): DatabaseFileObservation {
  val normalized = path.toAbsolutePath().normalize()
  val attributes =
    try {
      Files.readAttributes(normalized, BasicFileAttributes::class.java)
    } catch (failure: IOException) {
      throw when (failure) {
        is NoSuchFileException -> databaseMissing(normalized.toString(), operation)
        else -> databaseAccessFailure(normalized.toString(), operation, "file identity cannot be read", failure)
      }
    }
  val key =
    attributes.fileKey()?.toString()
      ?: throw databaseAccessFailure(
        normalized.toString(),
        operation,
        "stable file identity is unavailable",
      )
  return DatabaseFileObservation(normalized, key)
}

internal fun Connection.databasePath(): Path {
  val url = metaData.url ?: error("sqlite connection url is missing")
  val raw = url.removePrefix("jdbc:sqlite:")
  return Path.of(raw).toAbsolutePath().normalize()
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

internal fun databaseAccessError(
  dbPath: Path,
  operation: DatabaseAccessOperation,
  error: SQLException,
): SkillBillRuntimeException {
  if (error.isSqliteBusy()) return databaseBusy(error)
  val normalizedPath = dbPath.toAbsolutePath().normalize()
  if (Files.notExists(normalizedPath)) return databaseMissing(normalizedPath.toString(), operation)
  if (error.isSqliteCorrupt()) return databaseCorrupt(normalizedPath.toString(), operation, error)
  return databaseAccessFailure(
    dbPath = normalizedPath.toString(),
    operation = operation,
    condition = "sqlite result code ${error.errorCode}",
    cause = error,
  )
}

private fun Throwable.isSqliteBusy(): Boolean =
  generateSequence(this) { it.cause }.any { failure ->
    failure is SQLiteException &&
      (failure.resultCode.code and SQLITE_PRIMARY_CODE_MASK) == SQLiteErrorCode.SQLITE_BUSY.code
  }

private fun Throwable.isSqliteCorrupt(): Boolean =
  generateSequence(this) { it.cause }.any { failure ->
    failure is SQLiteException &&
      (failure.resultCode.code and SQLITE_PRIMARY_CODE_MASK) in SQLITE_CORRUPT_PRIMARY_CODES
  }

private const val SQLITE_PRIMARY_CODE_MASK = 0xFF
private val SQLITE_CORRUPT_PRIMARY_CODES =
  setOf(SQLiteErrorCode.SQLITE_CORRUPT.code, SQLiteErrorCode.SQLITE_NOTADB.code)

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
