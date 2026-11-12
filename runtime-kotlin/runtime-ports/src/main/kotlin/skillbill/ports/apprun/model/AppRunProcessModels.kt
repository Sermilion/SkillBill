package skillbill.ports.apprun.model

import java.nio.file.Path

data class AppRunProcessSpec(
  val command: List<String>,
  val workingDirectory: Path,
  val environment: Map<String, String>,
)

data class AppRunProcessExit(val exitCode: Int, val stopped: Boolean)
