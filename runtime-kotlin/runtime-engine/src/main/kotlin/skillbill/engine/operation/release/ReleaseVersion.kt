package skillbill.engine.operation.release

import skillbill.error.operation.MissingReleaseBumpError

enum class ReleaseBump(val wireValue: String) {
  PATCH("patch"),
  MINOR("minor"),
  MAJOR("major"),
  ;

  companion object {
    fun parse(raw: String?): ReleaseBump =
      entries.firstOrNull { it.wireValue == raw } ?: throw MissingReleaseBumpError(raw)
  }
}

/** The next stable `vMAJOR.MINOR.PATCH` tag after [lastTag] (none means `v0.0.0`) for [bump]. */
internal fun nextReleaseVersion(
  lastTag: String?,
  bump: ReleaseBump,
): String {
  val (major, minor, patch) =
    lastTag?.removePrefix("v")?.split('.')?.map(String::toInt) ?: listOf(0, 0, 0)
  return when (bump) {
    ReleaseBump.MAJOR -> "v${major + 1}.0.0"
    ReleaseBump.MINOR -> "v$major.${minor + 1}.0"
    ReleaseBump.PATCH -> "v$major.$minor.${patch + 1}"
  }
}
