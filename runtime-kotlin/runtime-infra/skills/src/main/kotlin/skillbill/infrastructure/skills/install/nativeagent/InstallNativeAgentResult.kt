package skillbill.infrastructure.skills.install.nativeagent

import skillbill.error.core.SkillBillRuntimeException
import java.nio.file.Path

sealed class InstallNativeAgentResult {
  data class Linked(val link: Path) : InstallNativeAgentResult()

  data class Skipped(val link: Path, val reason: String) : InstallNativeAgentResult()

  data class Failed(
    val failedPath: Path,
    val error: SkillBillRuntimeException,
  ) : InstallNativeAgentResult()
}
