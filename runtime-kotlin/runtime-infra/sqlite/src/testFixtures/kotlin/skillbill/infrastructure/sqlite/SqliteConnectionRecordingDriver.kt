package skillbill.infrastructure.sqlite

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverPropertyInfo
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.Properties
import java.util.logging.Logger

class SqliteConnectionRecordingDriver(
  private val delegate: Driver,
  private val onConnection: (Connection) -> Unit = {},
) : Driver {
  val categories = mutableListOf<String>()
  var pragmaTableInfoQueries: Int = 0
    private set

  fun clear() {
    categories.clear()
    pragmaTableInfoQueries = 0
  }

  override fun connect(
    url: String?,
    properties: Properties?,
  ): Connection? {
    val connection = delegate.connect(url, properties) ?: return null
    val wrapped =
      Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java),
      ) { _, method, args ->
        val value = invokeRaw(method, connection, args)
        when {
          value is PreparedStatement && method.name == "prepareStatement" ->
            recordStatement(value, args?.firstOrNull() as? String, prepared = true)
          value is Statement && method.name == "createStatement" ->
            recordStatement(value, null, prepared = false)
          else -> value
        }
      } as Connection
    onConnection(wrapped)
    return wrapped
  }

  private fun recordStatement(
    statement: Statement,
    preparedSql: String?,
    prepared: Boolean,
  ): Statement =
    Proxy.newProxyInstance(
      Statement::class.java.classLoader,
      arrayOf(if (prepared) PreparedStatement::class.java else Statement::class.java),
    ) { _, method, args ->
      if (method.name in setOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch")) {
        val sql = preparedSql ?: (args?.firstOrNull() as? String).orEmpty()
        categories += statementCategory(sql)
        if (sql.contains("pragma_table_info", ignoreCase = true)) {
          pragmaTableInfoQueries += 1
        }
      }
      invokeRaw(method, statement, args)
    } as Statement

  private fun statementCategory(sql: String): String {
    val normalized = sql.replace(Regex("\\s+"), " ").trim().uppercase()
    return when {
      isConnectionPragma(normalized) -> "connection_pragmas"
      normalized.startsWith("PRAGMA") -> "readiness_signal"
      isTransactionControl(normalized) -> "transaction_control"
      isSchemaMaintenance(normalized) -> "schema_maintenance"
      isRepairScan(normalized) -> "repair_scan"
      else -> "application_work"
    }
  }

  private fun isConnectionPragma(sql: String): Boolean =
    sql.startsWith("PRAGMA BUSY_TIMEOUT") ||
      sql.startsWith("PRAGMA JOURNAL_MODE") ||
      sql.startsWith("PRAGMA FOREIGN_KEYS")

  private fun isTransactionControl(sql: String): Boolean =
    sql.startsWith("BEGIN") ||
      sql.startsWith("COMMIT") ||
      sql.startsWith("ROLLBACK")

  private fun isSchemaMaintenance(sql: String): Boolean =
    sql.contains("SCHEMA_MIGRATIONS") ||
      sql.startsWith("CREATE TABLE") ||
      sql.startsWith("CREATE INDEX") ||
      sql.startsWith("ALTER TABLE") ||
      sql.startsWith("DROP TABLE") ||
      sql.startsWith("DROP INDEX")

  private fun isRepairScan(sql: String): Boolean =
    sql.contains("SQLITE_MASTER") ||
      sql.contains("PRAGMA_TABLE_INFO") ||
      (
        sql.contains(
          "STATE_ENTERED_AT_ESTIMATED",
        ) && !sql.contains("EXCLUDED") && !sql.contains("WHERE WORKFLOW_ID = ?")
      )

  override fun acceptsURL(url: String?): Boolean = delegate.acceptsURL(url)

  override fun getPropertyInfo(
    url: String?,
    properties: Properties?,
  ): Array<DriverPropertyInfo> = delegate.getPropertyInfo(url, properties)

  override fun getMajorVersion(): Int = delegate.majorVersion

  override fun getMinorVersion(): Int = delegate.minorVersion

  override fun jdbcCompliant(): Boolean = delegate.jdbcCompliant()

  override fun getParentLogger(): Logger = delegate.parentLogger

  private fun invokeRaw(
    method: Method,
    target: Any,
    args: Array<out Any?>?,
  ): Any? =
    try {
      method.invoke(target, *(args ?: emptyArray()))
    } catch (error: InvocationTargetException) {
      throw error.targetException
    }
}
