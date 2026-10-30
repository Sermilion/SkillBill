package skillbill.ports.process.model

const val DEFAULT_INSTALLER_PROCESS_DEADLINE_SECONDS: Long = 600L

data class InstallerProcessRequest(
  val executable: String,
  val arguments: List<String>,
  val environment: Map<String, String>,
  val deadlineSeconds: Long = DEFAULT_INSTALLER_PROCESS_DEADLINE_SECONDS,
)

data class InstallerProcessResult(
  val exitCode: Int,
  val output: String,
  val timedOut: Boolean = false,
  val launchFailure: Boolean = false,
)
