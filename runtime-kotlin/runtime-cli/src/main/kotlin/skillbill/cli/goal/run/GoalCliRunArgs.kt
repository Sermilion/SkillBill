package skillbill.cli.goal.run

import java.nio.file.Path

internal data class GoalRunInputValidationArgs(
  val issueKey: String?,
  val stopAfterSubtask: Int?,
  val agentAddonSlugs: List<String>,
  val agentAddonSelectionJson: String?,
  val agent: String?,
  val agentOverride: String?,
)

internal data class GoalRunAgentAddonHydrationArgs(
  val agentAddonSlugs: List<String>,
  val agentAddonSelectionJson: String?,
  val receivingAgents: List<String>,
  val effectiveRepoRoot: Path,
)
