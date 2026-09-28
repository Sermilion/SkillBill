package skillbill.engine.featuretask.slot.writehistory

internal object BoundaryMemoryPromptRules {
  val section: String =
    listOf(
      "## Boundary memory inputs",
      "Derive the history inputs (feature name, feature size, primary module/package/area, affected areas, " +
        "feature flag or `N/A`, acceptance criteria coverage, change summary) from this briefing, the spec it " +
        "references, and the current diff.",
      "The user explanation the decision rules name is the reasoning the spec, plan, or implementation states " +
        "for this change.",
    ).joinToString("\n\n")
}
