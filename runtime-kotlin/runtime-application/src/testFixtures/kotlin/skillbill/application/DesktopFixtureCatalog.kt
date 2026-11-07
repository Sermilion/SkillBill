package skillbill.application

import skillbill.workflow.model.WorkflowStatus

data class DesktopFixtureWorkflowCase(
  val id: String,
  val status: WorkflowStatus,
)

data class DesktopFixtureGoalCase(
  val id: String,
  val issueKey: String,
  val status: WorkflowStatus,
  val bundleDirectory: String,
  val pauseRequested: Boolean = false,
  val paused: Boolean = false,
  val liveLease: Boolean = false,
)

data class DesktopFixtureVerifyCase(
  val id: String,
  val issueKey: String,
  val status: WorkflowStatus,
  val currentStep: String,
  val supersededBy: String? = null,
  val stale: Boolean = false,
)

data class DesktopFixtureStandaloneCase(
  val id: String,
  val phaseId: String,
  val lifecycle: String,
)

data class DesktopFixtureBundleCase(
  val directory: String,
  val issueKey: String?,
  val expectedRunState: String,
  val manifestStatus: String = "pending",
  val goalId: String? = null,
)

object DesktopFixtureCatalog {
  const val ISSUE_PREFIX = "QAFIX"

  val taskWorkflows = WorkflowStatus.entries.map { DesktopFixtureWorkflowCase("workflow-${it.wireValue}", it) }

  val goals =
    listOf(
      DesktopFixtureGoalCase(
        "goal-running",
        "QAFIX-101",
        WorkflowStatus.RUNNING,
        "QAFIX-101-running",
        liveLease = true,
      ),
      DesktopFixtureGoalCase(
        "goal-pause-requested",
        "QAFIX-102",
        WorkflowStatus.RUNNING,
        "QAFIX-102-pause-requested",
        pauseRequested = true,
        liveLease = true,
      ),
      DesktopFixtureGoalCase("goal-paused", "QAFIX-103", WorkflowStatus.PAUSED, "QAFIX-103-paused", paused = true),
      DesktopFixtureGoalCase("goal-blocked", "QAFIX-104", WorkflowStatus.BLOCKED, "QAFIX-104-blocked"),
      DesktopFixtureGoalCase("goal-failed", "QAFIX-105", WorkflowStatus.FAILED, "QAFIX-105-failed"),
      DesktopFixtureGoalCase("goal-abandoned", "QAFIX-106", WorkflowStatus.ABANDONED, "QAFIX-106-canceled"),
      DesktopFixtureGoalCase("goal-completed", "QAFIX-107", WorkflowStatus.COMPLETED, "QAFIX-107-goal-completed"),
      DesktopFixtureGoalCase("goal-idle", "QAFIX-108", WorkflowStatus.RUNNING, "QAFIX-108-idle"),
    )

  val verifies =
    listOf(
      DesktopFixtureVerifyCase(
        "verify-superseded",
        "QAFIX-110",
        WorkflowStatus.ABANDONED,
        "code_review",
        "fixture-verify-running",
      ),
      DesktopFixtureVerifyCase("verify-running", "QAFIX-110", WorkflowStatus.RUNNING, "code_review"),
      DesktopFixtureVerifyCase("verify-parked", "QAFIX-110", WorkflowStatus.PENDING, "extract_criteria"),
      DesktopFixtureVerifyCase("verify-pending", "QAFIX-110", WorkflowStatus.PENDING, "gather_diff"),
      DesktopFixtureVerifyCase("verify-stopped", "QAFIX-110", WorkflowStatus.FAILED, "code_review"),
      DesktopFixtureVerifyCase("verify-completed", "QAFIX-110", WorkflowStatus.COMPLETED, "finish"),
      DesktopFixtureVerifyCase(
        "verify-stale-running",
        "QAFIX-110",
        WorkflowStatus.RUNNING,
        "code_review",
        stale = true,
      ),
    )

  val standalone =
    listOf(
      DesktopFixtureStandaloneCase("standalone-active", "review", "active"),
      DesktopFixtureStandaloneCase("standalone-paused", "validation", "paused"),
      DesktopFixtureStandaloneCase("standalone-blocked", "qa", "blocked"),
      DesktopFixtureStandaloneCase("standalone-failed", "review", "failed"),
      DesktopFixtureStandaloneCase("standalone-canceled", "validation", "canceled"),
      DesktopFixtureStandaloneCase("standalone-terminal", "qa", "terminal"),
    )

  val bundles =
    listOf(
      DesktopFixtureBundleCase("QAFIX-120-not-started", "QAFIX-120", "NOT_STARTED"),
      DesktopFixtureBundleCase("QAFIX-121-complete", "QAFIX-121", "COMPLETE", "complete"),
      DesktopFixtureBundleCase("qa-fixture-notes", null, "UNAVAILABLE"),
      goalBundle("goal-running", "RUNNING"),
      goalBundle("goal-pause-requested", "PAUSE_REQUESTED"),
      goalBundle("goal-paused", "PAUSED"),
      goalBundle("goal-blocked", "BLOCKED"),
      goalBundle("goal-failed", "BLOCKED"),
      goalBundle("goal-abandoned", "CANCELED"),
      goalBundle("goal-completed", "COMPLETE"),
      goalBundle("goal-idle", "PAUSED"),
    )

  private fun goalBundle(
    goalId: String,
    expectedRunState: String,
  ): DesktopFixtureBundleCase {
    val goal = goals.single { it.id == goalId }
    val manifestStatus = if (expectedRunState == "COMPLETE") "complete" else "pending"
    return DesktopFixtureBundleCase(
      goal.bundleDirectory,
      goal.issueKey,
      expectedRunState,
      manifestStatus,
      goal.id,
    )
  }
}
