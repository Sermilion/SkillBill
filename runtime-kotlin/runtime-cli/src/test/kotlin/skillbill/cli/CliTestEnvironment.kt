package skillbill.cli

import skillbill.contracts.persistence.RuntimeDatabaseEnvironmentKeys
import skillbill.contracts.system.OpenCodeEnvironmentKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path

private const val CODEX_HOME_ENV = "CODEX_HOME"
private val DATABASE_PATH_ENV = RuntimeDatabaseEnvironmentKeys.DATABASE_PATH
private val GOAL_LAUNCH_ENV_KEYS =
  with(FeatureTaskRuntimeGoalContinuationLaunchTokens) {
    setOf(
      GOAL_CONTINUATION_ENV,
      GOAL_PARENT_ISSUE_KEY_ENV,
      GOAL_SUBTASK_ID_ENV,
      GOAL_BRANCH_ENV,
      SUPPRESS_PR_ENV,
      GOAL_PARENT_WORKFLOW_ID_ENV,
      GOAL_LAST_RESUMABLE_STEP_ENV,
      CODE_REVIEW_MODE_ENV,
      VALIDATION_DEPTH_ENV,
      QUALITY_GATE_SELECTION_ENV,
      QA_SELECTION_ENV,
    )
  }

internal fun isolatedProjectStoreEnvironment(): Map<String, String> =
  isolatedCliEnvironment() +
    (STATE_DIR_ENVIRONMENT_KEY to Files.createTempDirectory("skillbill-cli-state").toString())

internal fun isolatedCliEnvironment(home: Path? = null): Map<String, String> {
  val filtered =
    System.getenv().filterKeys { key ->
      key != CODEX_HOME_ENV && key !in GOAL_LAUNCH_ENV_KEYS &&
        key != DATABASE_PATH_ENV && key != STATE_DIR_ENVIRONMENT_KEY &&
        key !in OpenCodeEnvironmentKeys.ALL
    }
  if (home == null) {
    return filtered
  }
  val codexHome = home.resolve(".codex")
  return if (Files.isDirectory(codexHome)) {
    filtered + (CODEX_HOME_ENV to codexHome.toString())
  } else {
    filtered
  }
}
