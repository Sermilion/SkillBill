package skillbill.engine.apprun

import java.nio.file.Path

public sealed interface AppRunGoalRoutingInput {
  public val repoRoot: Path

  public data class Live(
    override val repoRoot: Path,
    public val checkout: Path,
    public val indexTreeIdentity: String,
  ) : AppRunGoalRoutingInput

  public data class Completed(
    override val repoRoot: Path,
    public val commitSha: String,
  ) : AppRunGoalRoutingInput
}

public sealed interface AppRunGoalRoutingInputResult {
  public data class Ready(val input: AppRunGoalRoutingInput) : AppRunGoalRoutingInputResult

  public data class Failed(val reason: String) : AppRunGoalRoutingInputResult
}
