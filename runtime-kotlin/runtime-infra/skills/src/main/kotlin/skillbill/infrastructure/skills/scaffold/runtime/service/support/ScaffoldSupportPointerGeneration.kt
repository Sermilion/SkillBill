package skillbill.infrastructure.skills.scaffold.runtime.service.support

import skillbill.infrastructure.skills.scaffold.platformpack.loader.FEATURE_TASK_ADDON_CONSUMER
import skillbill.infrastructure.skills.scaffold.platformpack.loader.skillclass.SKILL_CLASSES_DIR
import skillbill.infrastructure.skills.scaffold.platformpack.loader.skillclass.discoverSkillClasses
import skillbill.infrastructure.skills.scaffold.platformpack.loader.skillclass.resolveSkillClass
import skillbill.infrastructure.skills.scaffold.platformpack.selectedPlatformManifests
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import java.nio.file.Files
import java.nio.file.Path

internal fun featureAddonPointerSpecsFor(
  skillName: String,
  selectedPlatformManifests: List<PlatformManifest>,
): List<PointerSpec> {
  if (skillName != "skill-bill") {
    return emptyList()
  }
  return selectedPlatformManifests.flatMap(::featureAddonPointersForManifest).distinctBy { spec -> spec.name }
}

private fun featureAddonPointersForManifest(manifest: PlatformManifest): List<PointerSpec> {
  val pointersByName =
    manifest.pointers
      .filter { spec -> spec.skillRelativeDir == FEATURE_TASK_ADDON_CONSUMER }
      .associateBy { it.name }
  return manifest.featureAddonUsage
    .filter { usage -> usage.consumer == FEATURE_TASK_ADDON_CONSUMER }
    .flatMap { usage -> usage.addons.flatMap { addon -> listOf(addon.entrypoint) + addon.companionPointers } }
    .mapNotNull(pointersByName::get)
}

internal fun requiredSupportingFilesForSkill(
  skillName: String,
  repoRoot: Path,
  selectedPlatformManifests: List<PlatformManifest> = emptyList(),
): List<String> {
  if (!Files.isDirectory(repoRoot.resolve(SKILL_CLASSES_DIR))) return emptyList()
  val skillClass = resolveSkillClass(skillName, discoverSkillClasses(repoRoot))
  val classPointers = skillClass?.pointers?.map { "$it.md" }.orEmpty()
  return classPointers + featureAddonPointerSpecsFor(skillName, selectedPlatformManifests).map { it.name }
}
