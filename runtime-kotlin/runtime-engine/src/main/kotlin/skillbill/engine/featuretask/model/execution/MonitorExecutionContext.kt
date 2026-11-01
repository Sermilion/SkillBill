package skillbill.engine.featuretask.model.execution

import skillbill.ports.monitor.model.MonitorResolvedTarget
import skillbill.ports.monitor.model.MonitorWorktree
import java.nio.file.Path

public data class MonitorExecutionContext(
  public val target: MonitorResolvedTarget,
  public val callerRoot: Path,
  public val primaryRoot: Path,
  public val worktree: MonitorWorktree,
  public val ownerToken: String,
  public val generation: Long,
  public val pinnedBaseSha: String,
)
