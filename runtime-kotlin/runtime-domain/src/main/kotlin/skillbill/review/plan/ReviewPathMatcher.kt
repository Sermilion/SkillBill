package skillbill.review.plan

object ReviewPathMatcher {
  fun matches(
    path: String,
    signal: String,
  ): Boolean = CompiledReviewPathMatcher(signal).matches(path)

  fun isIgnored(path: String): Boolean {
    val segments = path.lowercase().split('/')
    val fileName = segments.last()
    return segments.any { it in ignoredPathSegments } || fileName.endsWith(".d.ts") ||
      segments.any { it == "generated" || it.startsWith("generated-") }
  }

  private val ignoredPathSegments = setOf("node_modules", "dist", "build", "coverage", "target", "vendor", "vendored")
}

internal class CompiledReviewPathMatcher(signal: String) {
  private val normalizedSignal = signal.lowercase()
  private val regex =
    if ('*' in normalizedSignal) {
      val pattern = normalizedSignal.split('*').joinToString(".*") { Regex.escape(it) }
      Regex("^$pattern$")
    } else {
      null
    }

  fun matches(path: String): Boolean {
    val normalizedPath = path.lowercase()
    val regex = regex ?: return normalizedPath.contains(normalizedSignal)
    return regex.matches(normalizedPath) || regex.matches(normalizedPath.substringAfterLast('/'))
  }
}
