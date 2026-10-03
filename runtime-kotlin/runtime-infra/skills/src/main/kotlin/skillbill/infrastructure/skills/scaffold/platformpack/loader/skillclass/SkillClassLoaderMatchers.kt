package skillbill.infrastructure.skills.scaffold.platformpack.loader.skillclass

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import skillbill.error.shellcontent.invalidManifestSchema
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.model.SkillClassManifest
import skillbill.scaffold.model.SkillClassMatcher
import skillbill.scaffold.model.SkillClassSection

internal fun SkillClassManifest.matchesSkillName(skillName: String): Boolean {
  if (matchers.any { matcher -> matcher.excludeExact.contains(skillName) }) {
    return false
  }
  return matchers.any { matcher -> matcher.matchesSkillName(skillName) }
}

internal fun SkillClassMatcher.matchesSkillName(skillName: String): Boolean {
  val notExcluded = !excludeExact.contains(skillName)
  val localPattern = pattern
  val patternMatches = localPattern != null && localPattern.matches(skillName)
  return notExcluded && (exact == skillName || patternMatches)
}

internal fun readClassManifestYaml(
  classFile: Path,
  classId: String,
): Any? =
  try {
    Yaml().load<Any?>(Files.readString(classFile))
  } catch (error: CancellationException) {
    throw error
  } catch (error: IOException) {
    throw invalidManifestSchema(
      "Skill class '$classId': manifest '$classFile' is not valid YAML: ${error.message}",
      error,
    )
  } catch (error: YAMLException) {
    throw invalidManifestSchema(
      "Skill class '$classId': manifest '$classFile' is not valid YAML: ${error.message}",
      error,
    )
  }

internal fun buildClassManifest(
  classId: String,
  classFile: Path,
  raw: Any?,
): SkillClassManifest {
  val manifest = requireSkillClassManifestMap(classId, classFile.toString(), raw)
  val declaredClass = requireSkillClassString(manifest, classId, "class")
  validateDeclaredSkillClass(classId, declaredClass)
  val contractVersion = requireSkillClassString(manifest, classId, "contract_version")
  validateSkillClassContractVersion(classId, contractVersion)
  val matchers = parseSkillClassMatchers(manifest, classId)
  val pointers = parseSkillClassStringList(manifest, classId, "pointers", required = false)
  val sections = parseSkillClassSections(manifest, classId)
  val ceremonyLines = parseSkillClassStringList(manifest, classId, "ceremony_lines", required = false)
  return SkillClassManifest(
    classId = classId,
    classFile = classFile.toFileLocation(),
    contractVersion = contractVersion,
    matchers = matchers,
    pointers = pointers,
    sections = sections,
    ceremonyLines = ceremonyLines,
  )
}

internal fun parseSkillClassMatchers(
  manifest: Map<*, *>,
  classId: String,
): List<SkillClassMatcher> {
  val raw =
    manifest["matchers"]
      ?: throw invalidManifestSchema("Skill class '$classId': required field 'matchers' is missing.")
  val matchersList = requireSkillClassMatcherList(classId, raw)
  if (matchersList.isEmpty()) {
    throw invalidManifestSchema("Skill class '$classId': 'matchers' must declare at least one entry.")
  }
  return matchersList.mapIndexed { index, entry -> parseSkillClassMatcher(classId, index, entry) }
}

internal fun parseSkillClassSections(
  manifest: Map<*, *>,
  classId: String,
): List<SkillClassSection> {
  val raw = manifest["sections"] ?: return emptyList()
  val list =
    raw as? List<*>
      ?: throw invalidManifestSchema("Skill class '$classId': 'sections' must be a list.")
  return list.mapIndexed { index, entry -> parseSkillClassSection(classId, index, entry) }
}

internal fun parseSkillClassStringList(
  manifest: Map<*, *>,
  classId: String,
  field: String,
  required: Boolean,
): List<String> {
  val raw = manifest[field]
  if (raw == null) {
    if (required) {
      throw invalidManifestSchema("Skill class '$classId': required field '$field' is missing.")
    }
    return emptyList()
  }
  val list =
    raw as? List<*>
      ?: throw invalidManifestSchema("Skill class '$classId': '$field' must be a list of strings.")
  return list.mapIndexed { index, entry -> requireSkillClassStringEntry(classId, field, index, entry) }
}

internal fun requireSkillClassString(
  manifest: Map<*, *>,
  classId: String,
  field: String,
): String {
  val raw =
    manifest[field]
      ?: throw invalidManifestSchema("Skill class '$classId': required field '$field' is missing.")
  return raw as? String
    ?: throw invalidManifestSchema("Skill class '$classId': field '$field' must be a string.")
}
