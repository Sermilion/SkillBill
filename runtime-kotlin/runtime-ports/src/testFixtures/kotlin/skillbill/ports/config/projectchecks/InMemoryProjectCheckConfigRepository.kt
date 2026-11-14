package skillbill.ports.config.projectchecks

import skillbill.contracts.config.projectchecks.ProjectCheckConfig
import skillbill.contracts.config.projectchecks.ProjectCheckConfigDiscovery

class InMemoryProjectCheckConfigRepository(
  initial: ProjectCheckConfig? = null,
) : ProjectCheckConfigRepository {
  private val rows = mutableMapOf<String, ProjectCheckConfig>()

  init {
    initial?.let { rows[it.projectId] = it }
  }

  fun save(config: ProjectCheckConfig) {
    rows[config.projectId] = config
  }

  override fun load(projectId: String): ProjectCheckConfig? = rows[projectId]

  override fun fillMissing(
    projectId: String,
    discovery: ProjectCheckConfigDiscovery,
  ): ProjectCheckConfig {
    val current = rows[projectId] ?: ProjectCheckConfig(projectId, null, null, null, null, null)
    val filled =
      current.copy(
        buildCommands = current.buildCommands ?: discovery.buildCommands,
        lintCommands = current.lintCommands ?: discovery.lintCommands,
        testCommands = current.testCommands ?: discovery.testCommands,
        formattingCommands = current.formattingCommands ?: discovery.formattingCommands,
        validationCommands = current.validationCommands ?: discovery.validationCommands,
      )
    rows[projectId] = filled
    return filled
  }
}
