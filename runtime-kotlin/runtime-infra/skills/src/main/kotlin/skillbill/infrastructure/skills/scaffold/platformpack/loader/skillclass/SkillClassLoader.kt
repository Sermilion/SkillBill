package skillbill.infrastructure.skills.scaffold.platformpack.loader.skillclass

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.useDirectoryEntries
import skillbill.error.shellcontent.invalidManifestSchema
import skillbill.error.shellcontent.missingManifest
import skillbill.scaffold.model.SkillClassManifest

internal const val SKILL_CLASSES_DIR = "orchestration/skill-classes"

internal fun findRepoRootForSkillClasses(start: Path): Path? {
  var current: Path? = start.toAbsolutePath().normalize().let { if (Files.isDirectory(it)) it else it.parent }
  while (current != null) {
    if (Files.isDirectory(current.resolve(SKILL_CLASSES_DIR))) {
      return current
    }
    current = current.parent
  }
  return null
}

internal fun resolveSkillClassForSkill(
  skillName: String,
  startPath: Path,
): SkillClassManifest? {
  val repoRoot = findRepoRootForSkillClasses(startPath) ?: return null
  return resolveSkillClass(skillName, discoverSkillClasses(repoRoot))
}

internal fun discoverSkillClasses(repoRoot: Path): List<SkillClassManifest> {
  val classesDir = repoRoot.toAbsolutePath().normalize().resolve(SKILL_CLASSES_DIR)
  if (!Files.isDirectory(classesDir)) {
    throw missingManifest(
      "Skill classes directory '$classesDir' is missing. Every governed render needs at least the default class file.",
    )
  }
  val yamlFiles = classesDir.useDirectoryEntries("*.yaml") { stream -> stream.sorted().toList() }
  if (yamlFiles.isEmpty()) {
    throw missingManifest(
      "Skill classes directory '$classesDir' is empty. Expected at least one <class>.yaml file.",
    )
  }
  return yamlFiles.map(::loadSkillClassManifest)
}

internal fun resolveSkillClass(
  skillName: String,
  classes: List<SkillClassManifest>,
): SkillClassManifest? {
  val matches = classes.filter { manifest -> manifest.matchesSkillName(skillName) }
  return when {
    matches.isEmpty() -> null
    matches.size == 1 -> matches.single()
    else -> throw invalidManifestSchema(
      "Skill '$skillName' matches more than one class: ${matches.map { it.classId }.sorted()}. " +
        "Tighten the matchers (use exclude_exact or narrower patterns) so each skill resolves to exactly one class.",
    )
  }
}

internal fun loadSkillClassManifest(classFile: Path): SkillClassManifest {
  val resolved = classFile.toAbsolutePath().normalize()
  if (!Files.isRegularFile(resolved)) {
    throw missingManifest("Skill class manifest '$resolved' is missing.")
  }
  val classId = resolved.fileName.toString().removeSuffix(".yaml")
  val raw = readClassManifestYaml(resolved, classId)
  return buildClassManifest(classId, resolved, raw)
}
