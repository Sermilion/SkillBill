package skillbill.infrastructure.skills.scaffold.validation.review

import skillbill.infrastructure.skills.scaffold.rendering.defaultAreaFocus
import java.nio.file.Path

internal fun orderedFragments(
  content: String,
  vararg fragments: String,
): Boolean {
  val normalized = content.lowercase()
  return fragments.fold(-1) { previousIndex, fragment ->
    if (previousIndex == Int.MIN_VALUE) return@fold Int.MIN_VALUE
    normalized.indexOf(fragment.lowercase(), previousIndex + 1).takeIf { it >= 0 } ?: Int.MIN_VALUE
  } != Int.MIN_VALUE
}

internal fun containsAll(
  content: String,
  vararg fragments: String,
): Boolean = fragments.all { content.contains(it, ignoreCase = true) }

internal fun statesPositivePackDominance(rule: String): Boolean =
  containsAll(rule, "prefer", "dominat") && !rule.contains("do not prefer", ignoreCase = true)

internal fun statesAdjacentPackDisambiguation(rule: String): Boolean =
  containsAll(rule, "do not prefer") &&
    (rule.contains("adjacent", ignoreCase = true) || containsAll(rule, "another", "dominant", "stack")) &&
    !Regex("(?i)\\bdo not prefer\\s+(?:an?\\s+|the\\s+)?(?:adjacent|another\\s+dominant\\s+stack)")
      .containsMatchIn(rule)

internal fun isDefaultDerivedFocus(
  area: String,
  focus: String,
  packLabel: String,
): Boolean {
  val defaultFocus = defaultAreaFocus(area)
  return focus.equals(defaultFocus, ignoreCase = true) || focus.equals("$packLabel $defaultFocus", ignoreCase = true)
}

internal fun concreteFocusTerms(
  area: String,
  focus: String,
  packLabel: String,
): Set<String> {
  val generic = focusTerms("$packLabel ${area.replace('-', ' ')} ${defaultAreaFocus(area)}") + vagueFocusTerms
  return focusTerms(focus) - generic
}

internal fun focusTerms(value: String): Set<String> =
  Regex("[a-z0-9]+")
    .findAll(value.lowercase())
    .map(MatchResult::value)
    .filter { it.length > 2 }
    .toSet()

internal fun displayPath(
  pack: Path,
  path: Path,
): String =
  try {
    portablePath(pack.relativize(path))
  } catch (_: IllegalArgumentException) {
    path.toString()
  }

internal fun portablePath(path: Path): String = path.toString().replace('\\', '/')

internal fun reservedGeneratedSidecarNames(manifest: Map<*, *>): Set<String> {
  val pointers = manifest["pointers"] as? Map<*, *>
  val declaredNames =
    pointers.orEmpty().values.flatMap { entries ->
      (entries as? List<*>)?.filterIsInstance<Map<*, *>>()?.mapNotNull { it["name"] as? String }.orEmpty()
    }
  return (generatedSidecarNames + declaredNames).map(String::lowercase).toSet()
}
