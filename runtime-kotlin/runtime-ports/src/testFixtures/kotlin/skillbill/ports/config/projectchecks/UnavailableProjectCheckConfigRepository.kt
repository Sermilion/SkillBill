package skillbill.ports.config.projectchecks

import skillbill.contracts.config.projectchecks.ProjectCheckConfig
import skillbill.contracts.config.projectchecks.ProjectCheckConfigDiscovery

object UnavailableProjectCheckConfigRepository : ProjectCheckConfigRepository {
  override fun load(projectId: String): ProjectCheckConfig? = unavailable()

  override fun fillMissing(
    projectId: String,
    discovery: ProjectCheckConfigDiscovery,
  ): ProjectCheckConfig = unavailable()

  private fun unavailable(): Nothing =
    throw UnsupportedOperationException("Project check configuration is unavailable.")
}
