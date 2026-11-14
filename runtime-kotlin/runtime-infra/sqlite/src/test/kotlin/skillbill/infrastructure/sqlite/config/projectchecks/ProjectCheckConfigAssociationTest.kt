package skillbill.infrastructure.sqlite.config.projectchecks

import skillbill.contracts.config.projectchecks.ProjectCheckCategoryConfig
import skillbill.contracts.config.projectchecks.ProjectCheckCommand
import skillbill.contracts.config.projectchecks.ProjectCheckConfigDiscovery
import skillbill.infrastructure.host.CanonicalRepositoryRoot
import skillbill.infrastructure.host.projectstore.ProjectStoreResolver
import skillbill.infrastructure.sqlite.SqliteTestDiagnostics
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.model.EnvironmentContext
import skillbill.ports.projectstore.RepositoryLineagePort
import skillbill.ports.projectstore.model.RepositoryLineage
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectCheckConfigAssociationTest {
  @Test
  fun `stored commands follow stable promotion and explicit link association`() {
    val home = Files.createTempDirectory("project-check-association").toRealPath()
    val stateDir = home.resolve("state")
    val lineages = TestLineages()
    val projectStore =
      ProjectStoreResolver(
        CanonicalRepositoryRoot,
        lineages,
        SqliteTestDiagnostics,
        EnvironmentContext(
          environment = mapOf(STATE_DIR_ENVIRONMENT_KEY to stateDir.toString()),
          userHome = home,
        ),
        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
      )
    val database =
      sqliteDatabaseSessionFactory(
        userHome = home,
        dbPathOverride = home.resolve("runtime.db").toString(),
        environment = emptyMap(),
      )
    val firstCheckout = checkout(home, "first")
    val targetCheckout = checkout(home, "target")
    val firstCommand = listOf("./check-first")
    val targetCommand = listOf("./check-target")

    val firstId = projectStore.resolve(firstCheckout).projectId
    database.transaction { unit ->
      unit.projectCheckConfigs.fillMissing(firstId, discovery(firstCommand))
    }
    lineages.set(firstCheckout, LINEAGE_FIRST)
    assertEquals(firstId, projectStore.resolveCurrent(firstCheckout).projectId)
    assertEquals(
      firstCommand,
      database.read { it.projectCheckConfigs.load(firstId) }?.buildCommands?.commands?.single()?.argv,
    )

    val targetId = projectStore.resolve(targetCheckout).projectId
    database.transaction { unit ->
      unit.projectCheckConfigs.fillMissing(targetId, discovery(targetCommand))
    }
    projectStore.link(firstCheckout, targetId)

    val linkedId = projectStore.resolveCurrent(firstCheckout).projectId
    val linkedConfig = database.read { it.projectCheckConfigs.load(linkedId) }
    assertEquals(targetId, linkedId)
    assertEquals(targetCommand, linkedConfig?.buildCommands?.commands?.single()?.argv)
  }

  private fun discovery(command: List<String>) =
    ProjectCheckConfigDiscovery(
      ProjectCheckCategoryConfig(listOf(ProjectCheckCommand(command, "."))),
      null,
      null,
      null,
      null,
    )

  private fun checkout(
    home: Path,
    name: String,
  ): Path = Files.createDirectories(home.resolve(name).resolve(".git")).parent

  private class TestLineages : RepositoryLineagePort {
    private val values = mutableMapOf<Path, RepositoryLineage>()

    fun set(
      root: Path,
      lineage: RepositoryLineage,
    ) {
      values[root.toRealPath()] = lineage
    }

    override fun lineage(repoRoot: Path): RepositoryLineage? = values[repoRoot.toRealPath()]
  }

  private companion object {
    val LINEAGE_FIRST = RepositoryLineage(listOf("a".repeat(40)), "git@example.com:first/repo.git")
  }
}
