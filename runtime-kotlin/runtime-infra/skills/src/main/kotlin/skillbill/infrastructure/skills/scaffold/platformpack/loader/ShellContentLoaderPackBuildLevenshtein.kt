
package skillbill.infrastructure.skills.scaffold.platformpack.loader

import skillbill.error.shellcontent.invalidManifestSchema
import skillbill.infrastructure.skills.scaffold.platformpack.manifest.PlatformPackSchemaValidator
import java.nio.file.Path

internal fun guardAgainstAnchoredFieldTypos(
  slug: String,
  manifestPath: Path,
  customFieldKeys: Set<String>,
  anchoredKeys: Set<String>,
) {
  for (key in customFieldKeys) {
    for (anchored in anchoredKeys) {
      if (key == anchored) continue
      if (levenshtein1(key, anchored)) {
        throw invalidManifestSchema(
          "Platform pack '$slug' ($manifestPath) has a top-level field '$key' that looks like a typo " +
            "of the anchored field '$anchored' (did you mean '$anchored'?). Remove or rename the field — " +
            "non-anchored fields flow through customFields, but anchored field names are reserved.",
        )
      }
    }
  }
}

internal fun levenshtein1(
  a: String,
  b: String,
): Boolean {
  val lengthDelta = a.length - b.length
  if (lengthDelta < -1 || lengthDelta > 1 || a == b) return false
  return if (a.length == b.length) substitutionMatches(a, b) else insertionOrDeletionMatches(a, b)
}

internal fun substitutionMatches(
  a: String,
  b: String,
): Boolean {
  val diffs = a.indices.count { a[it] != b[it] }
  return diffs == 1
}

internal fun insertionOrDeletionMatches(
  a: String,
  b: String,
): Boolean {
  val longer = if (a.length > b.length) a else b
  val shorter = if (a.length > b.length) b else a
  var i = 0
  var j = 0
  var skipped = false
  while (i < longer.length && j < shorter.length) {
    if (longer[i] == shorter[j]) {
      i++
      j++
      continue
    }
    if (skipped) return false
    skipped = true
    i++
  }
  return true
}

internal val canonicalSchemaValidator: PlatformPackSchemaValidator by lazy {
  PlatformPackSchemaValidator()
}
