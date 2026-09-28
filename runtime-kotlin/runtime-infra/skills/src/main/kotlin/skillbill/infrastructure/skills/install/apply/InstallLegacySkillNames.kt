package skillbill.infrastructure.skills.install.apply

private val renamedSkillPairs: List<Pair<String, String>> =
  listOf(
    "bill-module-history" to "bill-boundary-history",
    "bill-code-review-architecture" to "bill-kotlin-code-review-architecture",
    "bill-backend-kotlin-code-review" to "bill-kotlin-code-review",
    "bill-code-review-backend-api-contracts" to "bill-kotlin-code-review-api-contracts",
    "bill-kotlin-code-review-backend-api-contracts" to "bill-kotlin-code-review-api-contracts",
    "bill-backend-kotlin-code-review-api-contracts" to "bill-kotlin-code-review-api-contracts",
    "bill-code-review-backend-persistence" to "bill-kotlin-code-review-persistence",
    "bill-kotlin-code-review-backend-persistence" to "bill-kotlin-code-review-persistence",
    "bill-backend-kotlin-code-review-persistence" to "bill-kotlin-code-review-persistence",
    "bill-code-review-backend-reliability" to "bill-kotlin-code-review-reliability",
    "bill-kotlin-code-review-backend-reliability" to "bill-kotlin-code-review-reliability",
    "bill-backend-kotlin-code-review-reliability" to "bill-kotlin-code-review-reliability",
    "bill-code-review-compose-check" to "bill-kmp-code-review-ui",
    "bill-kotlin-code-review-compose-check" to "bill-kmp-code-review-ui",
    "bill-kmp-code-review-compose-check" to "bill-kmp-code-review-ui",
    "bill-code-review-performance" to "bill-kotlin-code-review-performance",
    "bill-code-review-platform-correctness" to "bill-kotlin-code-review-platform-correctness",
    "bill-code-review-security" to "bill-kotlin-code-review-security",
    "bill-code-review-testing" to "bill-kotlin-code-review-testing",
    "bill-code-review-ux-accessibility" to "bill-kmp-code-review-ux-accessibility",
    "bill-kotlin-code-review-ux-accessibility" to "bill-kmp-code-review-ux-accessibility",
    "bill-feature-implement" to "bill-feature",
    "bill-kotlin-feature-implement" to "bill-feature",
    "bill-feature-implement-agentic" to "bill-feature",
    "bill-kotlin-feature-verify" to "bill-feature-verify",
    "bill-quality-check" to "bill-code-check",
    "bill-code-quality-check" to "bill-code-check",
    "bill-kotlin-quality-check" to "bill-kotlin-code-check",
    "bill-kotlin-code-quality-check" to "bill-kotlin-code-check",
    "bill-gcheck" to "bill-code-check",
  )

private val retiredSkillNames: List<String> =
  listOf(
    "bill-create-skill",
    "bill-grill-plan",
    "bill-skill-remove",
    "bill-skill-scaffold",
    "bill-new-skill-all-agents",
    "bill-feature",
    "bill-feature-spec",
    "bill-code-review",
    "bill-code-review-inline",
    "bill-code-check",
    "bill-pr-description",
    "bill-boundary-history",
    "bill-boundary-decisions",
    "bill-pr-review-fix",
    "bill-unit-test-value-check",
    "bill-update-check",
    "bill-release",
    "bill-feature-verify",
    "bill-feature-guard",
    "bill-feature-guard-cleanup",
    "bill-monitor",
  )

internal fun legacySkillBillCleanupNames(currentSkillNames: List<String>): List<String> =
  buildList {
    add(".bill-shared")
    currentSkillNames
      .filter { name -> name.startsWith("bill-") }
      .forEach { name -> add("mdp-${name.removePrefix("bill-")}") }
    renamedSkillPairs.forEach { (oldName, _) ->
      add(oldName)
      add("mdp-${oldName.removePrefix("bill-")}")
    }
    retiredSkillNames.forEach { name ->
      add(name)
      add("mdp-${name.removePrefix("bill-")}")
    }
  }.distinct()

internal fun legacySkillBillCacheNames(currentSkillNames: List<String>): List<String> =
  (renamedSkillPairs.map { (oldName, _) -> oldName } + retiredSkillNames)
    .filter { name -> name !in currentSkillNames }
    .distinct()
