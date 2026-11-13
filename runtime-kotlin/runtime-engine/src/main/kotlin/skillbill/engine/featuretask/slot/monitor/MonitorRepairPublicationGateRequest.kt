package skillbill.engine.featuretask.slot.monitor

import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot
import java.nio.file.Path

public data class MonitorRepairPublicationGateRequest(
  public val repoRoot: Path,
  public val recordedBaseBranch: String?,
  public val validationSnapshot: ProjectCheckCommandSnapshot?,
)
