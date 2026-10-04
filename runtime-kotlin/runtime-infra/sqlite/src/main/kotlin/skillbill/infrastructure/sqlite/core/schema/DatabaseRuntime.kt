package skillbill.infrastructure.sqlite.core.schema

import org.sqlite.SQLiteConfig
import skillbill.error.core.DatabaseAccessOperation
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.databaseAccessFailure
import skillbill.infrastructure.sqlite.core.migration.DatabaseMigrations
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

internal data class OpenDatabase(
  internal val connection: Connection,
  internal val dbPath: Path,
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
    return openWriteDbAt(dbPath)
  }

  fun openWriteDbAt(dbPath: Path): OpenDatabase =
    OpenDatabase(connection = openWriteConnectionAt(dbPath), dbPath = dbPath)

  fun establishSchemaReadiness(
    path: Path,
    diagnostics: RuntimeDiagnostics,
  ) {
    path.parent?.toAbsolutePath()?.normalize()?.toFile()?.mkdirs()
    asTypedFailure(path, DatabaseAccessOperation.OPEN) {
      DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath().normalize()}").use { connection ->
        connection.closingOnFailure {
          asTypedFailure(path, DatabaseAccessOperation.OPEN) {
            configureConnection(connection, enableWal = true)
            DatabaseSchema.createBaseSchema(connection)
            DatabaseMigrations.apply(connection, diagnostics)
          }
        }
      }
    }
  }

  fun openWriteConnectionAt(path: Path): Connection {
    val connection =
      asTypedFailure(path, DatabaseAccessOperation.OPEN) {
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath().normalize()}")
      }
    return connection.closingOnFailure {
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
    if (!Files.exists(dbPath) || isSchemaless(dbPath)) {
      return openDbAt(dbPath, diagnostics)
    }
    return openReadOnlyDb(dbPath)
  }

  internal fun openReadConnectionAt(dbPath: Path): OpenDatabase = openReadOnlyDb(dbPath)

  fun openReadDbIfPresentAt(dbPath: Path): OpenDatabase? {
    if (!Files.exists(dbPath)) return null
    if (isSchemaless(dbPath)) {
      throw databaseAccessFailure(
        dbPath = dbPath.toAbsolutePath().normalize().toString(),
        operation = DatabaseAccessOperation.READ,
        condition = "database schema is missing",
      )
    }
    return openReadOnlyDb(dbPath)
  }

  private fun openReadOnlyDb(dbPath: Path): OpenDatabase {
    val connection =
      asTypedFailure(dbPath, DatabaseAccessOperation.READ) {
        DriverManager.getConnection(
          "jdbc:sqlite:${dbPath.toAbsolutePath().normalize()}",
          SQLiteConfig().apply { setReadOnly(true) }.toProperties(),
        )
      }
    return connection.closingOnFailure {
      asTypedFailure(dbPath, DatabaseAccessOperation.READ) {
        configureConnection(connection, enableWal = false)
      }
      OpenDatabase(connection = connection, dbPath = dbPath)
    }
  }

  fun ensureDatabase(
    path: Path,
    diagnostics: RuntimeDiagnostics,
  ): Connection {
    establishSchemaReadiness(path, diagnostics)
    return openWriteConnectionAt(path)
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
    }
  }
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

private fun <T> Connection.closingOnFailure(block: () -> T): T {
  var succeeded = false
  return try {
    block().also { succeeded = true }
  } finally {
    if (!succeeded) {
      runCatching { close() }
    }
  }
}

internal fun databaseAccessError(
  dbPath: Path,
  operation: DatabaseAccessOperation,
  error: SQLException,
): SkillBillRuntimeException =
  databaseAccessFailure(
    dbPath = dbPath.toAbsolutePath().normalize().toString(),
    operation = operation,
    condition = "sqlite result code ${error.errorCode}: ${error.message.orEmpty()}",
    cause = error,
  )
