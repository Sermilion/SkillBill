package skillbill.ports.config.projectchecks

import skillbill.contracts.config.projectchecks.ProjectCheckConfig
import skillbill.contracts.config.projectchecks.ProjectCheckConfigDiscovery

interface ProjectCheckConfigRepository {
  fun load(projectId: String): ProjectCheckConfig?

  fun fillMissing(
    projectId: String,
    discovery: ProjectCheckConfigDiscovery,
  ): ProjectCheckConfig
}
