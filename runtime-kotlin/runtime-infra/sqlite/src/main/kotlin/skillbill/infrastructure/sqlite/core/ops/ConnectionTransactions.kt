package skillbill.infrastructure.sqlite.core.ops

import skillbill.error.core.DatabaseAccessOperation
import skillbill.error.core.failureCodeLabel
import skillbill.infrastructure.sqlite.core.schema.databaseAccessError
import skillbill.infrastructure.sqlite.core.schema.databasePath
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Types

private const val ROLLBACK_FAILURE_DETAIL_LIMIT = 240

internal enum class DatabaseTransactionBeginMode(val sql: String) {
  IMMEDIATE("BEGIN IMMEDIATE"),
  DEFERRED("BEGIN DEFERRED"),
}

internal data class DatabaseTransactionSpec(
  val dbPath: Path,
  val beginMode: DatabaseTransactionBeginMode,
  val operation: DatabaseAccessOperation,
  val diagnostics: RuntimeDiagnostics,
  val mapSqlExceptions: Boolean = true,
)

internal inline fun <T> Connection.inDatabaseTransaction(
  spec: DatabaseTransactionSpec,
  block: Connection.() -> T,
): T {
  executeTransactionStatement(spec.beginMode.sql)
  var committed = false
  var primaryFailure: Throwable? = null
  return try {
    val result =
      runCatching { block() }.getOrElse { failure ->
        primaryFailure =
          when (failure) {
            is SQLException ->
              if (spec.mapSqlExceptions) {
                mapTransactionBlockFailure(spec.dbPath, spec.operation, failure)
              } else {
                failure
              }
            is RuntimeException -> failure
            else -> throw failure
          }
        throw primaryFailure
      }
    commitWriteTransaction { failure -> primaryFailure = failure }
    committed = true
    result
  } finally {
    if (!committed) {
      rollbackAfterFailedTransaction(spec.dbPath, spec.diagnostics, primaryFailure)
    }
  }
}

private fun mapTransactionBlockFailure(
  dbPath: Path,
  operation: DatabaseAccessOperation,
  failure: SQLException,
): Throwable =
  when (operation) {
    DatabaseAccessOperation.WRITE -> databaseAccessError(dbPath, DatabaseAccessOperation.WRITE, failure)
    DatabaseAccessOperation.READ -> databaseAccessError(dbPath, DatabaseAccessOperation.READ, failure)
    else -> failure
  }

private fun Connection.executeTransactionStatement(sql: String) {
  createStatement().use { it.execute(sql) }
}

private inline fun Connection.commitWriteTransaction(onFailure: (SQLException) -> Unit) {
  try {
    executeTransactionStatement("COMMIT")
  } catch (failure: SQLException) {
    onFailure(failure)
    throw failure
  }
}

internal fun Connection.rollbackAfterFailedTransaction(
  dbPath: Path,
  diagnostics: RuntimeDiagnostics,
  primaryFailure: Throwable?,
) {
  val rollbackOutcome = runCatching { createStatement().use { it.execute("ROLLBACK") } }
  val rollbackFailure = rollbackOutcome.exceptionOrNull() ?: return
  if (primaryFailure != null && primaryFailure !== rollbackFailure) {
    primaryFailure.addSuppressed(rollbackFailure)
  }
  runCatching { logTransactionRollbackFailure(dbPath, diagnostics, rollbackFailure, primaryFailure) }
}

internal fun logTransactionRollbackFailure(
  dbPath: Path,
  diagnostics: RuntimeDiagnostics,
  rollbackFailure: Throwable,
  primaryFailure: Throwable?,
) {
  val rollbackDetail = boundedTransactionFailureDetail(rollbackFailure)
  val primaryDetail = primaryFailure?.let(::boundedTransactionFailureDetail) ?: "none"
  val dbLocation = dbPath.toAbsolutePath().normalize()
  diagnostics.warning(
    "skillbill sqlite: transaction rollback failed after transaction failure at $dbLocation; " +
      "rollback=$rollbackDetail; primary=$primaryDetail",
    rollbackFailure,
  )
}

private fun boundedTransactionFailureDetail(failure: Throwable): String {
  val message = failure.message?.takeIf { it.isNotBlank() }
  return (message ?: failure.failureCodeLabel() ?: failure::class.simpleName.orEmpty())
    .take(ROLLBACK_FAILURE_DETAIL_LIMIT)
}

internal inline fun <T> Connection.inNestedWriteTransaction(
  diagnostics: RuntimeDiagnostics,
  block: Connection.() -> T,
): T =
  inDatabaseTransaction(
    DatabaseTransactionSpec(
      dbPath = this.databasePath(),
      beginMode = DatabaseTransactionBeginMode.IMMEDIATE,
      operation = DatabaseAccessOperation.WRITE,
      diagnostics = diagnostics,
      mapSqlExceptions = false,
    ),
    block = block,
  )

internal fun PreparedStatement.bindAll(values: Iterable<*>) {
  values.forEachIndexed { index, value -> bindParameter(index + 1, value) }
}

internal fun PreparedStatement.bindAll(vararg values: Any?) {
  bindAll(values.asList())
}

private fun PreparedStatement.bindParameter(
  parameterIndex: Int,
  value: Any?,
) {
  when (value) {
    null -> setNull(parameterIndex, Types.NULL)
    is String -> setString(parameterIndex, value)
    is Int -> setInt(parameterIndex, value)
    is Long -> setLong(parameterIndex, value)
    is Boolean -> setBoolean(parameterIndex, value)
    is ByteArray -> setBytes(parameterIndex, value)
    else -> throw IllegalArgumentException("Unsupported bind value at index $parameterIndex: ${value::class}")
  }
}
