package skillbill.infrastructure.sqlite.config.projectchecks

import skillbill.contracts.config.projectchecks.ProjectCheckCategoryConfig
import skillbill.contracts.config.projectchecks.ProjectCheckCommand
import skillbill.contracts.config.projectchecks.ProjectCheckConfigDiscovery
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.projectchecks.ProjectCheckConfigFailureCode
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteProjectCheckConfigStoreTest {
  @Test
  fun `first committed category wins and invalid discovery publishes no partial values`() {
    val home = Files.createTempDirectory("project-check-config").toRealPath()
    val database = sqliteDatabaseSessionFactory(userHome = home, environment = emptyMap())
    val knownEmpty = ProjectCheckCategoryConfig(emptyList())

    assertNull(database.read { it.projectCheckConfigs.load("p-project") })

    val first =
      database.transaction { unit ->
        unit.projectCheckConfigs.fillMissing(
          "p-project",
          ProjectCheckConfigDiscovery(knownEmpty, null, null, null, null),
        )
      }
    assertEquals(emptyList(), first.buildCommands?.commands)

    val command = ProjectCheckCommand(listOf("check", ""), ".")
    database.transaction { unit ->
      unit.projectCheckConfigs.fillMissing(
        "p-project",
        ProjectCheckConfigDiscovery(
          ProjectCheckCategoryConfig(listOf(command)),
          null,
          ProjectCheckCategoryConfig(listOf(command)),
          null,
          null,
        ),
      )
    }
    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        database.transaction { unit ->
          unit.projectCheckConfigs.fillMissing(
            "p-project",
            ProjectCheckConfigDiscovery(
              null,
              null,
              ProjectCheckCategoryConfig(listOf(command)),
              ProjectCheckCategoryConfig(listOf(command.copy(workingDirectory = "../outside"))),
              null,
            ),
          )
        }
      }

    val committed = database.read { it.projectCheckConfigs.load("p-project") }
    assertEquals(emptyList(), committed?.buildCommands?.commands)
    assertEquals(listOf(command), committed?.testCommands?.commands)
    assertNull(committed?.lintCommands)
    assertTrue(failure.message.orEmpty().contains("p-project"))
  }

  @Test
  fun `invalid stored categories fail through owned errors and leave their original bytes intact`() {
    val home = Files.createTempDirectory("project-check-config-corrupt").toRealPath()
    val dbPath = home.resolve("runtime.db")
    val database =
      sqliteDatabaseSessionFactory(
        userHome = home,
        dbPathOverride = dbPath.toString(),
        environment = emptyMap(),
      )
    database.transaction { unit ->
      unit.projectCheckConfigs.fillMissing("p-corrupt", ProjectCheckConfigDiscovery(null, null, null, null, null))
    }
    val invalidCategories =
      listOf(
        "{",
        """{"commands":[],"commands":[]}""",
        """{"commands":[{"argv":["check",1e999],"working_directory":"."}]}""",
      )
    invalidCategories.forEach { raw ->
      replaceBuildCategory(dbPath, "p-corrupt", raw)
      val failure =
        assertFailsWith<SkillBillRuntimeException> {
          database.read { it.projectCheckConfigs.load("p-corrupt") }
        }
      val publicationFailure =
        assertFailsWith<SkillBillRuntimeException> {
          database.transaction {
            it.projectCheckConfigs.fillMissing("p-corrupt", ProjectCheckConfigDiscovery(null, null, null, null, null))
          }
        }

      assertEquals(ProjectCheckConfigFailureCode.PROJECT_CHECK_CONFIG_INVALID, failure.code)
      assertEquals(ProjectCheckConfigFailureCode.PROJECT_CHECK_CONFIG_INVALID, publicationFailure.code)
      assertEquals(raw, readBuildCategory(dbPath, "p-corrupt"))
      assertTrue(failure.message.orEmpty().contains("build_commands"))
      assertTrue(failure.message.orEmpty().contains(dbPath.toString()))
    }
  }

  @Test
  fun `filling another category preserves the first writer's original JSON bytes`() {
    val home = Files.createTempDirectory("project-check-config-retained").toRealPath()
    val dbPath = home.resolve("runtime.db")
    val database =
      sqliteDatabaseSessionFactory(
        userHome = home,
        dbPathOverride = dbPath.toString(),
        environment = emptyMap(),
      )
    database.transaction {
      it.projectCheckConfigs.fillMissing("p-project", ProjectCheckConfigDiscovery(null, null, null, null, null))
    }
    val original = """{ "commands" : [ { "working_directory" : ".", "argv" : ["check", ""] } ] }"""
    replaceBuildCategory(dbPath, "p-project", original)

    val committed =
      database.transaction {
        it.projectCheckConfigs.fillMissing(
          "p-project",
          ProjectCheckConfigDiscovery(
            ProjectCheckCategoryConfig(emptyList()),
            ProjectCheckCategoryConfig(emptyList()),
            null,
            null,
            null,
          ),
        )
      }

    assertEquals(original, readBuildCategory(dbPath, "p-project"))
    assertEquals(listOf("check", ""), committed.buildCommands?.commands?.single()?.argv)
    assertEquals(emptyList(), committed.lintCommands?.commands)
  }

  private fun replaceBuildCategory(
    dbPath: Path,
    projectId: String,
    raw: String,
  ) {
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.prepareStatement(
        "UPDATE project_config SET build_commands = ? WHERE project_id = ?",
      ).use { statement ->
        statement.setString(1, raw)
        statement.setString(2, projectId)
        statement.executeUpdate()
      }
    }
  }

  private fun readBuildCategory(
    dbPath: Path,
    projectId: String,
  ): String =
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.prepareStatement("SELECT build_commands FROM project_config WHERE project_id = ?").use { statement ->
        statement.setString(1, projectId)
        statement.executeQuery().use { rows ->
          assertTrue(rows.next())
          rows.getString(1)
        }
      }
    }
}
