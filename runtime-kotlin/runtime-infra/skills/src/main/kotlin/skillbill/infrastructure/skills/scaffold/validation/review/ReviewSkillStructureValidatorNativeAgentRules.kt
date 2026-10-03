package skillbill.infrastructure.skills.scaffold.validation.review

import java.nio.file.Path
import skillbill.error.shellcontent.invalidReviewSkillStructure

internal fun violation(
  path: Path,
  rule: String,
) = ReviewSkillStructureViolation(path, rule)

internal fun invalidNativeAgentBundle(
  path: Path,
  error: Exception,
): Nothing =
  throw invalidReviewSkillStructure(
    "$path: invalid native-agent source bundle: ${error.message}",
    error,
  )
