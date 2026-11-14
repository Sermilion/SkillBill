package skillbill.engine.featuretask.slot.monitor

import skillbill.engine.featuretask.model.phase.ValidationCommandLaunchTransport
import java.nio.file.Path

public data class MonitorRepairPublicationGateRequest(
  public val repoRoot: Path,
  public val recordedBaseBranch: String?,
  public val validationTransport: ValidationCommandLaunchTransport?,
)
