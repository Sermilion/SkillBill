package skillbill.infrastructure.launcher.process.cursor

internal fun resolveAcpModel(
  requested: String,
  advertised: List<String>,
): String? {
  if (requested in advertised) return requested
  val byBase = advertised.groupBy { it.substringBefore('[').lowercase() }
  return slugBaseCandidates(requested).firstNotNullOfOrNull { base -> byBase[base]?.singleOrNull() }
}

private fun slugBaseCandidates(requested: String): List<String> {
  var slug = requested.substringBefore('[').trim().lowercase().removePrefix(CURSOR_SLUG_PREFIX)
  if (slug == AUTO_SLUG) return listOf(AUTO_BASE)
  val candidates = mutableListOf(slug)
  while (true) {
    val token = slug.substringAfterLast('-', missingDelimiterValue = "")
    if (token !in VARIANT_TOKENS || slug.length == token.length + 1) break
    slug = slug.removeSuffix("-$token")
    candidates += slug
  }
  return candidates
}

private const val CURSOR_SLUG_PREFIX = "cursor-"
private const val AUTO_SLUG = "auto"
private const val AUTO_BASE = "default"
private val VARIANT_TOKENS = setOf("fast", "none", "minimal", "low", "medium", "high", "xhigh", "max", "thinking")
