package skillbill.infrastructure.skills.scaffold.authoring

import skillbill.error.shellcontent.invalidInternalSkillClassification
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal data class InternalSkillDeclaration(
  val skillName: String,
  val contentFile: Path,
  val declaredParent: String?,
  val isBaseSkill: Boolean,
)

internal fun internalSkillClassificationViolations(declarations: Collection<InternalSkillDeclaration>): List<String> {
  val byName = declarations.associateBy { declaration -> declaration.skillName }
  return declarations.mapNotNull { declaration ->
    val declaredParent = declaration.declaredParent ?: return@mapNotNull null
    val prefix = "${declaration.contentFile}: internal skill '${declaration.skillName}'"
    when {
      declaredParent.isBlank() ->
        "$prefix declares parent via 'internal-for:' with an empty value; the value must be the " +
          "name of another discovered skill."
      declaredParent == declaration.skillName ->
        "$prefix declares parent '$declaredParent' which is the skill itself; an internal skill's " +
          "parent must be a different discovered skill."
      else -> parentViolation(prefix, declaredParent, byName[declaredParent])
    }
  }
}

internal fun parentViolation(
  prefix: String,
  declaredParent: String,
  parent: InternalSkillDeclaration?,
): String? =
  when {
    parent == null ->
      "$prefix declares parent '$declaredParent' which is not a discovered skill."
    !parent.isBaseSkill ->
      "$prefix declares parent '$declaredParent' which is a platform-pack skill; an internal " +
        "skill's parent must be a listed base skill under skills/."
    parent.declaredParent != null ->
      "$prefix declares parent '$declaredParent' which is itself an internal skill (chained " +
        "internal-for is not allowed; depth is 1)."
    else -> null
  }

internal fun requireValidInternalSkillClassification(declarations: Collection<InternalSkillDeclaration>) {
  internalSkillClassificationViolations(declarations).firstOrNull()?.let { violation ->
    throw invalidInternalSkillClassification(violation)
  }
}

internal fun validateInternalSkillClassification(targets: Map<String, AuthoringTarget>) {
  requireValidInternalSkillClassification(
    targets.values.map { target ->
      InternalSkillDeclaration(
        skillName = target.skillName,
        contentFile = target.contentFile,
        declaredParent = target.internalFor,
        isBaseSkill = target.platform.isBlank(),
      )
    },
  )
}

internal fun parseInternalForFrontmatter(contentFile: Path): String? {
  if (!Files.isRegularFile(contentFile, LinkOption.NOFOLLOW_LINKS)) {
    return null
  }
  val match = FRONTMATTER_PATTERN.find(Files.readString(contentFile)) ?: return null
  return match.groupValues[1].lineSequence().mapNotNull { line ->
    val separator = line.indexOf(':')
    if (separator < 0) {
      null
    } else {
      val parsedKey = line.substring(0, separator).trim()
      val parsedValue = line.substring(separator + 1).trim().trim('"', '\'')
      if (parsedKey == "internal-for") parsedValue else null
    }
  }.firstOrNull()
}

private val FRONTMATTER_PATTERN = Regex("""(?s)\A---\n(.*?)\n---\n""")
