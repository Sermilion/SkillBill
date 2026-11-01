package skillbill.infrastructure.skills.install.staging

import skillbill.infrastructure.skills.scaffold.platformpack.selectedPlatformManifests
import skillbill.infrastructure.skills.scaffold.runtime.service.support.requireSupportingFileTarget
import skillbill.infrastructure.skills.scaffold.runtime.service.support.requiredSupportingFilesForSkill
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path

internal data class GeneratedSupportPointer(
  val name: String,
  val target: Path,
)

internal fun generatedSupportPointersFor(
  repoRoot: Path,
  sourceSkillDir: Path,
  skillName: String,
  skillsRoot: Path = repoRoot.resolve("skills"),
  selectedPlatformManifests: List<PlatformManifest> = emptyList(),
): List<GeneratedSupportPointer> {
  val root = repoRoot.toAbsolutePath().normalize()
  val resolvedSkillsRoot = skillsRoot.toAbsolutePath().normalize()
  val resolvedSource = sourceSkillDir.toAbsolutePath().normalize()
  if (!resolvedSource.startsWith(resolvedSkillsRoot)) {
    return emptyList()
  }
  return requiredSupportingFilesForSkill(skillName, root, selectedPlatformManifests).mapNotNull { fileName ->
    val target =
      requireSupportingFileTarget(skillName, fileName, root, selectedPlatformManifests)
        .toAbsolutePath()
        .normalize()
    val sourceSidecar = resolvedSource.resolve(fileName).normalize()
    if (target == sourceSidecar) {
      null
    } else {
      GeneratedSupportPointer(fileName, target)
    }
  }
}
