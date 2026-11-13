package skillbill.engine.featuretask.model.phase

import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot
import skillbill.contracts.config.projectchecks.ProjectCheckCommand

public sealed class ValidationCommandLaunchTransport {
  public data class Commands(
    public val snapshot: ProjectCheckCommandSnapshot,
    public val rendered: String,
  ) : ValidationCommandLaunchTransport()

  public data class KnownEmpty(
    public val snapshot: ProjectCheckCommandSnapshot,
  ) : ValidationCommandLaunchTransport()

  public data class LegacyCommands(
    public val initialCommands: List<ProjectCheckCommand>,
    public val postRepairCommands: List<ProjectCheckCommand>,
    public val rendered: String,
  ) : ValidationCommandLaunchTransport()
}
