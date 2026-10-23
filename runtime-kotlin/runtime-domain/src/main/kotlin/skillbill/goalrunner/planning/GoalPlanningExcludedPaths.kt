package skillbill.goalrunner.planning

import skillbill.contracts.projectstore.LegacyProjectStatePaths

object GoalPlanningExcludedPaths {
  val EXCLUDED_ROOTS: List<String> = listOf("platform-packs/")

  val EXCLUDED_DIRECTORY_NAMES: List<String> =
    listOf(
      ".cache",
      ".git",
      ".gradle",
      ".idea",
      ".next",
      LegacyProjectStatePaths.RUNTIME_STATE_DIRECTORY,
      ".tox",
      ".venv",
      "DerivedData",
      "Pods",
      "__pycache__",
      "build",
      "dist",
      "node_modules",
      "out",
      "target",
      "vendor",
      "venv",
    )

  fun isExcluded(relativePath: String): Boolean {
    val normalized = normalize(relativePath) ?: return true
    if (normalized.isEmpty()) return false
    if (EXCLUDED_ROOTS.any { root -> "$normalized/".startsWith(root) }) return true
    return normalized.split("/").any { segment -> segment in EXCLUDED_DIRECTORY_NAMES }
  }

  private fun normalize(relativePath: String): String? {
    val segments = mutableListOf<String>()
    for (segment in relativePath.replace('\\', '/').split("/")) {
      when (segment) {
        "", "." -> Unit
        ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
        else -> segments.add(segment)
      }
    }
    return segments.joinToString("/")
  }
}
