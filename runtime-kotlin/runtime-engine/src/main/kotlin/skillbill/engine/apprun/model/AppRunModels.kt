package skillbill.engine.apprun.model

import skillbill.engine.featuretask.model.execution.QaNamedDevice
import java.nio.file.Path
import java.time.Instant

public data class AppRunRequest(
  val repoRoot: Path,
  val issueKey: String,
  val device: QaNamedDevice,
  val cancellation: AppRunCancellation = AppRunCancellation(),
)

public enum class AppRunStep { RESOLVING, BOOTING, LEASING, BUILDING, LAUNCHING }

public data class AppRunProgress(val step: AppRunStep, val detail: String? = null)

public interface AppRunListener {
  public fun onProgress(progress: AppRunProgress)

  public fun onOutput(line: String)
}

public sealed interface AppRunOutcome {
  public data class Running(
    val device: QaNamedDevice,
    val serial: String,
    val launched: String,
    val openedAt: Instant,
  ) : AppRunOutcome

  public data class BuildFailed(val device: QaNamedDevice, val task: String) : AppRunOutcome

  public data class ModuleUnresolved(val candidates: List<String>) : AppRunOutcome

  public data class LaunchFailed(val device: QaNamedDevice, val reason: String) : AppRunOutcome

  public data class BootFailed(val device: QaNamedDevice) : AppRunOutcome

  public data class Contended(val device: QaNamedDevice, val ownerInvocationId: String) : AppRunOutcome

  public data object Stopped : AppRunOutcome

  public data class Refused(val reason: AppRunRefusalReason, val message: String) : AppRunOutcome

  public data class Failed(val exitCode: Int?, val detail: String) : AppRunOutcome
}
