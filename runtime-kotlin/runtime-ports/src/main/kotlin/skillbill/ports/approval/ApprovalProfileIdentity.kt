package skillbill.ports.approval

import java.nio.file.InvalidPathException
import java.nio.file.Path

private const val CLAUDE_DEFAULT_PROFILE = "claude:default"
private const val CLAUDE_DIR_PROFILE_PREFIX = "claude:dir:"
private const val CLAUDE_DEFAULT_DIRECTORY = ".claude"

fun claudeProfileIdentity(
  configDir: String?,
  userHome: Path?,
): String {
  if (configDir.isNullOrBlank()) return CLAUDE_DEFAULT_PROFILE
  val directory =
    try {
      Path.of(configDir).normalize()
    } catch (_: InvalidPathException) {
      return ""
    }
  return when {
    !directory.isAbsolute -> ""
    userHome != null && directory == userHome.resolve(CLAUDE_DEFAULT_DIRECTORY).normalize() -> CLAUDE_DEFAULT_PROFILE
    else -> CLAUDE_DIR_PROFILE_PREFIX + directory
  }
}
