package skillbill.infrastructure.sqlite.core.migration

import skillbill.infrastructure.sqlite.core.ops.bindAll
import java.sql.Connection

internal object MigrationLedger {
  class State(
    val tableExists: Boolean,
    val versionKeyed: Boolean,
    val appliedVersions: Map<String, Int>,
  ) {
    val appliedNames: Set<String> get() = appliedVersions.keys

    fun hasPendingWork(migrations: List<DatabaseMigration>): Boolean =
      when {
        !tableExists -> true

        versionKeyed -> true

        else -> migrations.any { migration -> appliedVersions[migration.name] != migration.version }
      }
  }

  fun readState(connection: Connection): State {
    val exists = tableExists(connection)
    return State(
      tableExists = exists,
      versionKeyed = exists && versionIsPrimaryKey(connection),
      appliedVersions = if (exists) appliedVersions(connection) else emptyMap(),
    )
  }

  fun ensureNameKeyed(connection: Connection) {
    if (!tableExists(connection)) return
    if (!versionIsPrimaryKey(connection)) return

    connection.createStatement().use { statement ->
      statement.execute("ALTER TABLE schema_migrations RENAME TO schema_migrations_version_keyed")
      statement.execute(
        """
        CREATE TABLE schema_migrations (
          name TEXT PRIMARY KEY,
          version INTEGER NOT NULL,
          applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
        """.trimIndent(),
      )
      statement.execute(
        """
        INSERT INTO schema_migrations (name, version, applied_at)
        SELECT name, version, applied_at FROM schema_migrations_version_keyed
        """.trimIndent(),
      )
      statement.execute("DROP TABLE schema_migrations_version_keyed")
    }
  }

  fun appliedNames(connection: Connection): Set<String> =
    connection.prepareStatement(
      """
      SELECT name
      FROM schema_migrations
      ORDER BY version
      """.trimIndent(),
    ).use { statement ->
      statement.executeQuery().use { resultSet ->
        buildSet {
          while (resultSet.next()) {
            add(resultSet.getString("name"))
          }
        }
      }
    }

  fun alignVersions(
    connection: Connection,
    migrations: List<DatabaseMigration>,
  ) {
    connection.prepareStatement(
      "UPDATE schema_migrations SET version = ? WHERE name = ? AND version <> ?",
    ).use { statement ->
      migrations.forEach { migration ->
        statement.bindAll(migration.version, migration.name, migration.version)
        statement.executeUpdate()
      }
    }
  }

  fun record(
    connection: Connection,
    migration: DatabaseMigration,
  ) {
    connection.prepareStatement(
      """
      INSERT INTO schema_migrations (version, name)
      VALUES (?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(migration.version, migration.name)
      statement.executeUpdate()
    }
  }

  private fun appliedVersions(connection: Connection): Map<String, Int> =
    connection.prepareStatement("SELECT name, version FROM schema_migrations").use { statement ->
      statement.executeQuery().use { resultSet ->
        buildMap {
          while (resultSet.next()) {
            put(resultSet.getString("name"), resultSet.getInt("version"))
          }
        }
      }
    }

  private fun tableExists(connection: Connection): Boolean =
    connection.prepareStatement(
      "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations'",
    ).use { statement ->
      statement.executeQuery().use { resultSet -> resultSet.next() }
    }

  private fun versionIsPrimaryKey(connection: Connection): Boolean =
    connection.prepareStatement(
      "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations'",
    ).use { statement ->
      statement.executeQuery().use { resultSet ->
        resultSet.next() &&
          Regex("""\bversion\s+INTEGER\s+PRIMARY\s+KEY\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(resultSet.getString("sql").orEmpty())
      }
    }
}
