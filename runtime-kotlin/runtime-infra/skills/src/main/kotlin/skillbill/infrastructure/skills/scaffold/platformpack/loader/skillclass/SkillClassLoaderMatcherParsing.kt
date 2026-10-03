package skillbill.infrastructure.skills.scaffold.platformpack.loader.skillclass

import skillbill.error.shellcontent.invalidManifestSchema
import skillbill.scaffold.model.SkillClassMatcher

internal fun parseExcludeExactList(
  classId: String,
  index: Int,
  excludeExactRaw: Any?,
): List<String> =
  when (excludeExactRaw) {
    null -> emptyList()
    is List<*> ->
      excludeExactRaw.map { value ->
        value as? String
          ?: throw invalidManifestSchema(
            "Skill class '$classId': matcher #$index 'exclude_exact' entries must be strings.",
          )
      }
    else -> throw invalidManifestSchema(
      "Skill class '$classId': matcher #$index 'exclude_exact' must be a list of strings.",
    )
  }

internal fun parseMatcherPattern(
  classId: String,
  index: Int,
  patternString: String?,
): Regex? =
  patternString?.let { source ->
    runCatching { Regex(source) }.getOrElse { error ->
      throw invalidManifestSchema(
        "Skill class '$classId': matcher #$index pattern '$source' is not a valid regex: ${error.message}",
      )
    }
  }

internal fun parseSkillClassMatcher(
  classId: String,
  index: Int,
  raw: Any?,
): SkillClassMatcher {
  val entry = requireSkillClassMatcherMap(classId, index, raw)
  val exact = parseSkillClassMatcherExact(classId, index, entry)
  val patternString = parseSkillClassMatcherPattern(classId, index, entry)
  validateSkillClassMatcherShape(classId, index, exact, patternString)
  val excludeExact = parseExcludeExactList(classId, index, entry["exclude_exact"])
  val pattern = parseMatcherPattern(classId, index, patternString)
  return SkillClassMatcher(exact = exact, pattern = pattern, excludeExact = excludeExact)
}

private fun requireSkillClassMatcherMap(
  classId: String,
  index: Int,
  raw: Any?,
): Map<*, *> =
  raw as? Map<*, *> ?: throw invalidManifestSchema(
    "Skill class '$classId': matcher #$index must be a YAML mapping with 'exact' or 'pattern'.",
  )

private fun parseSkillClassMatcherExact(
  classId: String,
  index: Int,
  entry: Map<*, *>,
): String? =
  entry["exact"]?.let { value ->
    value as? String
      ?: throw invalidManifestSchema("Skill class '$classId': matcher #$index field 'exact' must be a string.")
  }

private fun parseSkillClassMatcherPattern(
  classId: String,
  index: Int,
  entry: Map<*, *>,
): String? =
  entry["pattern"]?.let { value ->
    value as? String
      ?: throw invalidManifestSchema("Skill class '$classId': matcher #$index field 'pattern' must be a string.")
  }

private fun validateSkillClassMatcherShape(
  classId: String,
  index: Int,
  exact: String?,
  patternString: String?,
) {
  if (exact == null && patternString == null) {
    throw invalidManifestSchema(
      "Skill class '$classId': matcher #$index must declare either 'exact' or 'pattern'.",
    )
  }
  if (exact != null && patternString != null) {
    throw invalidManifestSchema(
      "Skill class '$classId': matcher #$index must declare exactly one of 'exact' or 'pattern', not both.",
    )
  }
}
