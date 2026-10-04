
package skillbill.skillremove

import skillbill.model.FileLocation
import skillbill.skillremove.model.SkillRemovalRefusalReason
import skillbill.skillremove.model.SkillRemovalRequest
import skillbill.skillremove.model.SkillRemovalResult
import skillbill.skillremove.model.SkillRemovalTarget

object TargetValidation {
  private val NAME_REGEX: Regex = Regex("^[A-Za-z0-9._-]+$")

  fun refusal(request: SkillRemovalRequest): SkillRemovalResult.Refused? {
    val repoRoot = FileLocation(request.repoRootAbsolutePath).normalized()
    val problem: String? =
      when (val target = request.target) {
        is SkillRemovalTarget.HorizontalSkill -> nameProblem(target.skillName, "skillName")
        is SkillRemovalTarget.PlatformPack -> nameProblem(target.platform, "platform")
        is SkillRemovalTarget.AddOn -> validateAddOnRelativePath(target.relativePath, repoRoot)
        is SkillRemovalTarget.ExternalAddOn ->
          nameProblem(target.platform, "platform")
            ?: validateExternalAddOnPaths(target.sourceRootAbsolutePath, target.fileName)
      }
    return problem?.let { SkillRemovalResult.Refused(SkillRemovalRefusalReason.INVALID_TARGET, it) }
  }

  private fun nameProblem(
    name: String,
    field: String,
  ): String? =
    when {
      name.isBlank() -> "Invalid $field: must not be blank."
      name == "." || name == ".." -> "Invalid $field '$name': '.' and '..' are not valid identifiers."
      name.startsWith("-") -> "Invalid $field '$name': must not start with '-'."
      !NAME_REGEX.matches(name) -> "Invalid $field '$name': only [A-Za-z0-9._-] characters are allowed."
      else -> null
    }
}
