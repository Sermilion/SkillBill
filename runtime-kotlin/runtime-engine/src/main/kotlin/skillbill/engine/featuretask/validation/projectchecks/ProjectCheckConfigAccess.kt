package skillbill.engine.featuretask.validation.projectchecks

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.config.projectchecks.ProjectCheckConfig
import skillbill.contracts.config.projectchecks.ProjectCheckConfigDiscovery
import skillbill.ports.db.DatabaseSchemaPort
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.projectstore.model.ProjectStoreLocation
import java.nio.file.Path

public data class ProjectCheckConfigAccessSnapshot(
  public val location: ProjectStoreLocation,
  public val config: ProjectCheckConfig,
)

@Inject
public class ProjectCheckConfigAccess(
  private val databaseSchema: DatabaseSchemaPort,
  private val database: DatabaseSessionFactory,
  private val projectStore: ProjectStorePort,
) {
  public fun load(workingDirectory: Path): ProjectCheckConfigAccessSnapshot {
    databaseSchema.ensureDatabaseReady()
    val location = projectStore.resolveCurrent(workingDirectory)
    val config =
      database.read { it.projectCheckConfigs.load(location.projectId) }
        ?: ProjectCheckConfig(location.projectId, null, null, null, null, null)
    return ProjectCheckConfigAccessSnapshot(location, config)
  }

  public fun fillMissing(
    projectId: String,
    discovery: ProjectCheckConfigDiscovery,
  ): ProjectCheckConfig {
    databaseSchema.ensureDatabaseReady()
    return database.transaction { it.projectCheckConfigs.fillMissing(projectId, discovery) }
  }
}
