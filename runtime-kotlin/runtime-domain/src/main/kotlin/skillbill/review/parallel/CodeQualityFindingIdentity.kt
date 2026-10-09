package skillbill.review.parallel

fun isCodeQualityFinding(
  laneSkillNames: Iterable<String>,
  specialistSkillNames: Iterable<String>,
): Boolean =
  (laneSkillNames + specialistSkillNames).any { skillName ->
    skillName.endsWith("-code-quality")
  }
