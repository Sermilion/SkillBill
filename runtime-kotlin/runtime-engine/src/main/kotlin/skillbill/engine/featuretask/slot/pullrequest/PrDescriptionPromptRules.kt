package skillbill.engine.featuretask.slot.pullrequest

internal object PrDescriptionPromptRules {
  fun section(template: PullRequestTemplate): String = listOf(HEADING, templateRule(template)).joinToString("\n\n")

  private fun templateRule(template: PullRequestTemplate): String =
    when (template) {
      is PullRequestTemplate.Found ->
        "The runtime searched ${searchOrder()} and found the repo-native template `${template.path}` (its " +
          "checklist already removed):\n```markdown\n${template.content}\n```"
      is PullRequestTemplate.Absent ->
        "The runtime searched ${searchOrder()} and found no repo-native template. Use the built-in fallback " +
          "template."
      is PullRequestTemplate.Ambiguous ->
        "The runtime found several pull request templates and no default: ${template.paths.joinToString(", ")}."
    }

  private fun searchOrder(): String = PullRequestTemplateSearch.SEARCH_ORDER.joinToString(", ") { "`$it`" }

  private const val HEADING = "## Pull request template search result"
}
