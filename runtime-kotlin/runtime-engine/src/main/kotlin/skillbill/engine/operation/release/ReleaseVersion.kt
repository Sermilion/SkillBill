package skillbill.engine.operation.release

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
