package skillbill.infrastructure.skills.install.apply

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException
import java.nio.file.Path

internal enum class InstallApplyFailureCode : RuntimeFailureCode {
  SYMLINK,
}

internal data class InstallSymlinkFailure(
  val path: Path,
  val error: SkillBillRuntimeException,
)

internal fun symbolicLinkFailure(
  linkPath: Path,
  cause: Exception,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    InstallApplyFailureCode.SYMLINK,
    "Failed to create symlink at $linkPath. ${windowsSymlinkGuidance()}",
    cause,
  )

internal fun windowsSymlinkGuidance(): String =
  "On Windows, enable Developer Mode (Settings -> Privacy & security -> For developers) " +
    "or run the install command from an elevated shell so the JVM can create symlinks."
