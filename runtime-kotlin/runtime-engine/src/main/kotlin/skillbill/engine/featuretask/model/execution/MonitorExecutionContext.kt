package skillbill.engine.featuretask.model.execution

import skillbill.ports.monitor.model.MonitorResolvedTarget
import skillbill.ports.monitor.model.MonitorWorktree
import java.nio.file.Path

data class MonitorExecutionContext(
  val target: MonitorResolvedTarget,
  val callerRoot: Path,
  val worktree: MonitorWorktree,
  val ownerToken: String,
  val generation: Long,
)
