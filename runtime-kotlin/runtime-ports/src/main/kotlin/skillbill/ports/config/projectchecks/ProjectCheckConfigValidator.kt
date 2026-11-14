package skillbill.ports.config.projectchecks

import skillbill.contracts.config.projectchecks.ProjectCheckConfig

fun interface ProjectCheckConfigValidator {
  fun validate(
    document: String,
    projectId: String,
    databaseLocation: String,
  ): ProjectCheckConfig
}
