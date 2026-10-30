package skillbill.infrastructure.skills.install.symlink

import skillbill.error.core.SkillBillRuntimeException
import java.nio.file.Path

internal class InstallSymlinkException(
  val linkPath: Path,
  val guidance: String,
  cause: Exception,
) : SkillBillRuntimeException(
    "Failed to create symlink at $linkPath. $guidance",
    cause,
  )

internal fun windowsSymlinkGuidance(): String =
  "On Windows, enable Developer Mode (Settings -> Privacy & security -> For developers) " +
    "or run the install command from an elevated shell so the JVM can create symlinks."

internal fun symbolicLinkFailure(
  linkPath: Path,
  cause: Exception,
): SkillBillRuntimeException = InstallSymlinkException(linkPath, windowsSymlinkGuidance(), cause)
