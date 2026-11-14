package skillbill.engine.featuretask.validation.projectchecks

import skillbill.contracts.config.projectchecks.PROJECT_CHECK_DISCOVERY_CONTRACT_VERSION
import skillbill.contracts.config.projectchecks.ProjectCheckCategory
import skillbill.contracts.config.projectchecks.ProjectCheckConfigKeys
import skillbill.contracts.config.projectchecks.ProjectCheckDiscoveryKeys
import skillbill.contracts.config.projectchecks.wireValue
import skillbill.scaffold.model.ValidationGateDeclaration

internal object ProjectCheckCommandDiscoveryPrompt {
  fun build(
    projectId: String,
    checkoutRoot: String,
    packSlug: String,
    missingCategories: Set<ProjectCheckCategory>,
    validationGate: ValidationGateDeclaration,
  ): String =
    buildString {
      appendLine("Discover minimal project check commands for skill-bill project checks.")
      appendLine("Inspect source only. Do not edit files, execute project checks, or start workflows.")
      appendLine("Read applicable ancestor, repository-root, and scoped AGENTS.md instructions before inferring.")
      appendLine("When a nested Gradle root applies, resolve its checkout-relative directory explicitly.")
      appendLine("Preserve prescribed command order, argv, and directory. Use working_directory for directory changes.")
      appendLine("Preserve compound shell commands via an explicit interpreter argv; do not split or rewrite them.")
      appendLine(
        "Report unreadable required instructions or ambiguous build prescriptions as failure, not empty success.",
      )
      appendLine("Project id: $projectId")
      appendLine("Checkout root: $checkoutRoot")
      appendLine("Routed pack: $packSlug")
      val requested = missingCategories.sortedBy { it.wireValue }
      appendLine("Requested categories: ${requested.joinToString { it.wireValue }}")
      requested.forEach { category ->
        appendLine("Category ${category.wireValue}: ${categorySemantics(category)}")
        val fallback = manifestFallbackArgv(category, validationGate)
        if (fallback != null) {
          appendLine(
            "Manifest fallback argv (use only when no applicable prescription exists): ${argvTokens(fallback)}",
          )
        }
      }
      appendLine(responseFieldGuide())
      append("Include ")
      append(ProjectCheckDiscoveryKeys.PROJECT_ID)
      append(" ")
      append(projectId)
      append(" and only the requested keys under ")
      append(ProjectCheckDiscoveryKeys.CATEGORIES)
      append(".")
    }

  private fun categorySemantics(category: ProjectCheckCategory): String =
    when (category) {
      ProjectCheckCategory.BUILD ->
        "Compile-only build work for this project. Never promote full validation or check gates into build."
      ProjectCheckCategory.VALIDATION ->
        "The ordered full ordinary validation commands for this project. Do not concatenate other categories."
      ProjectCheckCategory.LINT ->
        "Independent lint commands only when not already covered by validation."
      ProjectCheckCategory.TEST ->
        "Independent test commands only when not already covered by validation."
      ProjectCheckCategory.FORMATTING ->
        "Independent formatting commands only when not already covered by validation."
    }

  private fun manifestFallbackArgv(
    category: ProjectCheckCategory,
    gate: ValidationGateDeclaration,
  ): List<String>? =
    when (category) {
      ProjectCheckCategory.BUILD -> gate.buildCommand
      ProjectCheckCategory.VALIDATION -> gate.fullGateCommand
      ProjectCheckCategory.LINT, ProjectCheckCategory.TEST, ProjectCheckCategory.FORMATTING -> null
    }

  private fun responseFieldGuide(): String =
    buildString {
      append("Reply with one JSON object and nothing else using ")
      append(ProjectCheckDiscoveryKeys.CONTRACT_VERSION)
      append("=")
      append(PROJECT_CHECK_DISCOVERY_CONTRACT_VERSION)
      append(", ")
      append(ProjectCheckDiscoveryKeys.PROJECT_ID)
      append(", and ")
      append(ProjectCheckDiscoveryKeys.CATEGORIES)
      append(" with only the requested category keys. Each category value contains ")
      append(ProjectCheckConfigKeys.COMMANDS)
      append(" arrays. Explicit empty arrays mean no applicable command was found after inspection.")
    }

  private fun argvTokens(argv: List<String>): String =
    argv.joinToString(prefix = "[", postfix = "]") { token ->
      "\"" + token.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
