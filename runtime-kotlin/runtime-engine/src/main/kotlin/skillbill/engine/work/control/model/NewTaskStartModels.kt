package skillbill.engine.work.control.model

import java.nio.file.Path
import java.util.concurrent.CompletionStage

public sealed interface NewTaskKeyAllocation {
  public data class Allocated(public val key: String) : NewTaskKeyAllocation

  public data class NeedsPrefix(public val choices: List<String>) : NewTaskKeyAllocation
}

public data class NewTaskIntakeRequest(
  public val requirements: String,
  public val title: String?,
  public val baseBranch: String?,
  public val keyChoice: String?,
  public val occupiedKeys: Set<String>,
  public val reuseRequested: Boolean = false,
  public val identityPolicy: NewTaskIdentityPolicy = NewTaskIdentityPolicy.REQUIREMENTS_FIRST,
)

public enum class NewTaskIdentityPolicy {
  REQUIREMENTS_FIRST,
  RESOLVED_DESKTOP_IDENTITY,
}

public sealed interface NewTaskPreparation {
  public data class Ready(
    public val key: String,
    public val intake: String,
    public val allocated: Boolean,
    public val reuseRequested: Boolean = false,
  ) : NewTaskPreparation

  public data class NeedsKey(public val choices: List<String>) : NewTaskPreparation

  public data class Refused(public val reason: String) : NewTaskPreparation
}

public enum class NewTaskRunKind { GOAL, PLAN }

public data class StandalonePhaseRequest(
  public val phaseId: String,
  public val target: String? = null,
)

public data class StandaloneCheckout(
  public val checkoutRoot: Path? = null,
  public val branch: String? = null,
)

public sealed interface NewTaskStartResult {
  public data object Started : NewTaskStartResult

  /** Started, and [failure] completes when the run exits: with why it failed, or null after a clean exit. */
  public data class Running(public val failure: CompletionStage<String?>) : NewTaskStartResult

  public data object KeyTaken : NewTaskStartResult

  public data class Refused(public val reason: String) : NewTaskStartResult
}
