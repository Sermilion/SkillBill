package skillbill.engine.featuretask.model.phase

import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot

public sealed class ValidationCommandLaunchTransport {
  public data class Commands(
    public val snapshot: ProjectCheckCommandSnapshot,
    public val rendered: String,
  ) : ValidationCommandLaunchTransport()

  public data class KnownEmpty(
    public val snapshot: ProjectCheckCommandSnapshot,
  ) : ValidationCommandLaunchTransport()
}
