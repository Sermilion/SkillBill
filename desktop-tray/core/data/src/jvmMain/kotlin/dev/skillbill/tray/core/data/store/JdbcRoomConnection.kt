package dev.skillbill.tray.core.data.store

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import org.sqlite.core.CoreStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

internal class JdbcRoomConnection(private val connection: Connection) : SQLiteConnection {
  private var transaction = false

  override fun inTransaction(): Boolean = transaction

  override fun prepare(sql: String): SQLiteStatement = jdbcOperation {
    JdbcRoomStatement(connection.prepareStatement(sql)) {
      when (sql.trim().substringBefore(' ').uppercase()) {
        "BEGIN" -> transaction = true
        "COMMIT", "END", "ROLLBACK" -> transaction = false
      }
    }
  }

  override fun close() = jdbcOperation { connection.close() }
}

private class JdbcRoomStatement(
  private val statement: PreparedStatement,
  private val executed: () -> Unit,
) : SQLiteStatement {
  private val native = statement as CoreStatement
  private var rows: ResultSet? = null
  private var started = false
  private var exhausted = false

  override fun bindBlob(index: Int, value: ByteArray) = jdbcOperation { statement.setBytes(index, value) }
  override fun bindDouble(index: Int, value: Double) = jdbcOperation { statement.setDouble(index, value) }
  override fun bindLong(index: Int, value: Long) = jdbcOperation { statement.setLong(index, value) }
  override fun bindText(index: Int, value: String) = jdbcOperation { statement.setString(index, value) }
  override fun bindNull(index: Int) = jdbcOperation { statement.setNull(index, Types.NULL) }
  override fun getBlob(index: Int): ByteArray = jdbcOperation { requireNotNull(rows).getBytes(index + 1) }
  override fun getDouble(index: Int): Double = jdbcOperation { requireNotNull(rows).getDouble(index + 1) }
  override fun getLong(index: Int): Long = jdbcOperation { requireNotNull(rows).getLong(index + 1) }
  override fun getText(index: Int): String = jdbcOperation { requireNotNull(rows).getString(index + 1) }
  override fun isNull(index: Int): Boolean = jdbcOperation { requireNotNull(rows).getObject(index + 1) == null }
  override fun getColumnCount(): Int = jdbcOperation { native.pointer.safeRunInt<SQLException> { database, pointer -> database.column_count(pointer) } }
  override fun getColumnName(index: Int): String = jdbcOperation { native.pointer.safeRun<String, SQLException> { database, pointer -> database.column_name(pointer, index) } }
  override fun getColumnType(index: Int): Int = jdbcOperation {
    when (requireNotNull(rows).getObject(index + 1)) {
      null -> 5
      is ByteArray -> 4
      is Float, is Double -> 2
      is Number -> 1
      else -> 3
    }
  }

  override fun step(): Boolean = jdbcOperation {
    if (exhausted) return@jdbcOperation false
    if (!started) {
      started = true
      if (statement.execute()) rows = statement.resultSet
      executed()
    }
    val available = rows?.next() == true
    if (!available) exhausted = true
    available
  }

  override fun reset() = jdbcOperation {
    rows?.close()
    rows = null
    started = false
    exhausted = false
  }

  override fun clearBindings() = jdbcOperation { statement.clearParameters() }
  override fun close() = jdbcOperation { statement.close() }
}

internal inline fun <T> jdbcOperation(block: () -> T): T = try {
  block()
} catch (failure: SQLException) {
  throw SQLiteException("Error code: ${failure.errorCode}, JDBC SQLite operation failed.").apply { initCause(failure) }
}
