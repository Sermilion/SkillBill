package skillbill.ports.config.projectchecks

import skillbill.contracts.config.projectchecks.ProjectCheckCategory
import skillbill.contracts.config.projectchecks.ProjectCheckDiscoveryResponse

fun interface ProjectCheckDiscoveryValidator {
  fun validate(
    document: String,
    projectId: String,
    requestedCategories: Set<ProjectCheckCategory>,
    responseLabel: String,
  ): ProjectCheckDiscoveryResponse
}
