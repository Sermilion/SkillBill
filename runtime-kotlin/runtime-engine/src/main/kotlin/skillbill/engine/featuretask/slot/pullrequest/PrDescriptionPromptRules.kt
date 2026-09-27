package skillbill.engine.featuretask.slot.pullrequest

internal object PrDescriptionPromptRules {
  fun section(template: PullRequestTemplate): String =
    listOf(HEADING, CONTEXT, templateRule(template), TITLE, WRITING).joinToString("\n\n")

  private fun templateRule(template: PullRequestTemplate): String =
    when (template) {
      is PullRequestTemplate.Found ->
        "The runtime searched ${searchOrder()} and found the repo-native template `${template.path}` (its " +
          "checklist already removed). Fill it: keep its headings, section order, and non-checklist placeholder " +
          "text exactly as authored, fill each section with concise reviewer-facing content from the change, do " +
          "not reshape it into another format, and add no checklist.\n" + fenced(template.content)
      is PullRequestTemplate.Absent ->
        "The runtime searched ${searchOrder()} and found no repo-native template. Use this fallback template:\n" +
          fenced(FALLBACK_TEMPLATE)
      is PullRequestTemplate.Ambiguous ->
        "The runtime found several pull request templates and no default: ${template.paths.joinToString(", ")}."
    }

  private fun searchOrder(): String = PullRequestTemplateSearch.SEARCH_ORDER.joinToString(", ") { "`$it`" }

  private fun fenced(markdown: String): String = "```markdown\n$markdown\n```"

  private const val HEADING = "## Pull request description rules"

  private const val CONTEXT =
    "Read the branch diff against the base branch this briefing names, with the commit log and branch name, " +
      "and the spec it references when that file exists. Honor CLAUDE.md, AGENTS.md, and the pull request " +
      "description section of `.agents/skill-overrides.md` when present."

  private const val TITLE =
    "Title: `[<issue key>] <descriptive title>` with the issue key in square brackets, then a concise, title-case " +
      "description of the user-visible outcome rather than the branch name or slug; keep it under 70 characters " +
      "when possible."

  private const val WRITING =
    "Writing rules:\n" +
      "- The summary explains why the change was made, not just which files changed.\n" +
      "- Test instructions are concrete enough for a reviewer to reproduce.\n" +
      "- If the change is behind a feature flag, say how to enable it for testing.\n" +
      "- Include the Media section for UI changes, or write \"N/A\".\n" +
      "- Do not include a checklist.\n" +
      "- Keep it concise."

  private const val FALLBACK_TEMPLATE =
    "# Summary\n\n" +
      "<1-3 sentences: what changed, why it matters, and the user-visible outcome. Reference the ticket/spec.>\n\n" +
      "<optional: bullet list of key changes if more than one logical change>\n\n" +
      "## Feature Flags\n\n" +
      "<flag name and description, or \"N/A\">\n\n" +
      "## Media\n\n" +
      "<screenshots or videos for UI changes, or \"N/A\">\n\n" +
      "# How Has This Been Tested?\n\n" +
      "<overview of tests performed — unit tests, manual verification, preview checks>\n\n" +
      "<reproducible test instructions:>\n" +
      "1. <step>\n" +
      "2. <step>\n" +
      "3. <expected result>"
}
