package skillbill.cli

import skillbill.contracts.persistence.RuntimeDatabaseEnvironmentKeys
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path

private const val CODEX_HOME_ENV = "CODEX_HOME"
private const val GOAL_CONTINUATION_ENV = "SKILL_BILL_GOAL_CONTINUATION"
private val DATABASE_PATH_ENV = RuntimeDatabaseEnvironmentKeys.DATABASE_PATH

internal fun isolatedProjectStoreEnvironment(): Map<String, String> =
  isolatedCliEnvironment() +
    (STATE_DIR_ENVIRONMENT_KEY to Files.createTempDirectory("skillbill-cli-state").toString())

internal fun isolatedCliEnvironment(home: Path? = null): Map<String, String> {
  val filtered =
    System.getenv().filterKeys { key ->
      key != CODEX_HOME_ENV && key != GOAL_CONTINUATION_ENV && key != DATABASE_PATH_ENV
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
