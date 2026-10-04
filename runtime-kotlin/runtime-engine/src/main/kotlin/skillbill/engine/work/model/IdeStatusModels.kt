package skillbill.engine.work.model

import skillbill.ports.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusWorkflowFamily
import skillbill.workflow.model.FeatureTaskRouteScope
import java.nio.file.Path
import java.time.Instant

enum class IdeStatusSelectionTier {
  ACTIVE,
  PAUSED,
  BLOCKED,
  FAILED,
  RECENTLY_TERMINAL,
  IDLE,
  ;

  val rank: Int get() = ordinal
}

data class IdeStatusCandidate(
  val workflowId: String,
  val workflowFamily: IdeStatusWorkflowFamily,
  val issueKey: String?,
  val currentState: String,
  val lifecycleState: IdeStatusLifecycleState,
  val selectionTier: IdeStatusSelectionTier,
  val updatedAt: Instant,
  val startedAt: Instant?,
  val routeScope: FeatureTaskRouteScope? = null,
  val isGoalAuthoritative: Boolean = workflowFamily == IdeStatusWorkflowFamily.FEATURE_GOAL,
)

sealed class IdeStatusRepositoryResolution {
  data class Ok(val identity: String, val repoRoot: Path) : IdeStatusRepositoryResolution()

  data class Invalid(val message: String) : IdeStatusRepositoryResolution()

  data class Missing(val message: String) : IdeStatusRepositoryResolution()
}

data class IdeStatusRequest(
  val repoRoot: String,
  val observedAt: Instant? = null,
) {
  init {
    require(repoRoot.isNotBlank()) { "repoRoot is required." }
  }
}

data class IdeStatusResult(
  val snapshot: IdeStatusSnapshot,
  val exitCode: Int,
)
