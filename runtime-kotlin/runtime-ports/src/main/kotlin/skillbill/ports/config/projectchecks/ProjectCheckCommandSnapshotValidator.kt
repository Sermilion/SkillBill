package skillbill.ports.config.projectchecks

import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot

interface ProjectCheckCommandSnapshotValidator {
  fun validate(
    document: String,
    sourceLabel: String,
  ): ProjectCheckCommandSnapshot
}
